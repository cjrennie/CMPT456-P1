/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.component;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.google.common.annotations.VisibleForTesting;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.conn.SchemeRegistryFactory;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpClientConfigurer;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.client.solrj.impl.LBHttpSolrClient;
import org.apache.solr.client.solrj.impl.LBHttpSolrClient.Builder;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.URLUtil;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrInfoMBean;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.SolrMetricProducer;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.update.UpdateShardHandler;
import org.apache.solr.update.UpdateShardHandlerConfig;
import org.apache.solr.util.DefaultSolrThreadFactory;
import org.apache.solr.util.stats.HttpClientMetricNameStrategy;
import org.apache.solr.util.stats.InstrumentedPoolingClientConnectionManager;
import org.apache.solr.util.stats.InstrumentedHttpClient;
import org.apache.solr.util.stats.MetricUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.util.stats.InstrumentedHttpRequestExecutor.KNOWN_METRIC_NAME_STRATEGIES;

public class HttpShardHandlerFactory extends ShardHandlerFactory implements org.apache.solr.util.plugin.PluginInfoInitialized, SolrMetricProducer {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final String DEFAULT_SCHEME = "http";
  
  // We want an executor that doesn't take up any resources if
  // it's not used, so it could be created statically for
  // the distributed search component if desired.
  //
  // Consider CallerRuns policy and a lower max threads to throttle
  // requests at some point (or should we simply return failure?)
  private ExecutorService commExecutor = new ExecutorUtil.MDCAwareThreadPoolExecutor(
      0,
      Integer.MAX_VALUE,
      5, TimeUnit.SECONDS, // terminate idle threads after 5 sec
      new SynchronousQueue<Runnable>(),  // directly hand off tasks
      new DefaultSolrThreadFactory("httpShardExecutor")
  );

  protected InstrumentedPoolingClientConnectionManager clientConnectionManager;
  protected CloseableHttpClient defaultClient;
  private LBHttpSolrClient loadbalancer;
  //default values:
  int soTimeout = UpdateShardHandlerConfig.DEFAULT_DISTRIBUPDATESOTIMEOUT;
  int connectionTimeout = UpdateShardHandlerConfig.DEFAULT_DISTRIBUPDATECONNTIMEOUT;
  int maxConnectionsPerHost = 20;
  int maxConnections = 10000;
  int corePoolSize = 0;
  int maximumPoolSize = Integer.MAX_VALUE;
  int keepAliveTime = 5;
  int queueSize = -1;
  boolean accessPolicy = false;
  boolean useRetries = false;
  int maxConnectionIdleTime = UpdateShardHandlerConfig.DEFAULT_MAXUPDATECONNECTIONIDLETIME;
  int connectionsEvictorSleepDelay = UpdateShardHandlerConfig.DEFAULT_UPDATECONNECTIONSEVICTORSLEEPDELAY;

  protected UpdateShardHandler.IdleConnectionsEvictor idleConnectionsEvictor;
  private WhitelistHostChecker whitelistHostChecker = null;

  private String scheme = null;

  private HttpClientMetricNameStrategy metricNameStrategy;

  protected final Random r = new Random();

  private final ReplicaListTransformer shufflingReplicaListTransformer = new ShufflingReplicaListTransformer(r);

  // URL scheme to be used in distributed search.
  static final String INIT_URL_SCHEME = "urlScheme";

  // The core size of the threadpool servicing requests
  static final String INIT_CORE_POOL_SIZE = "corePoolSize";

  // The maximum size of the threadpool servicing requests
  static final String INIT_MAX_POOL_SIZE = "maximumPoolSize";

  // The amount of time idle threads persist for in the queue, before being killed
  static final String MAX_THREAD_IDLE_TIME = "maxThreadIdleTime";

  // If the threadpool uses a backing queue, what is its maximum size (-1) to use direct handoff
  static final String INIT_SIZE_OF_QUEUE = "sizeOfQueue";

  // Configure if the threadpool favours fairness over throughput
  static final String INIT_FAIRNESS_POLICY = "fairnessPolicy";
  
  // Turn on retries for certain IOExceptions, many of which can happen
  // due to connection pooling limitations / races
  static final String USE_RETRIES = "useRetries";

  static final String CONNECTIONS_EVICTOR_SLEEP_DELAY = "connectionsEvictorSleepDelay";

  static final String MAX_CONNECTION_IDLE_TIME = "maxConnectionIdleTime";

  public static final String INIT_SHARDS_WHITELIST = "shardsWhitelist";

  static final String INIT_SOLR_DISABLE_SHARDS_WHITELIST = "solr.disable." + INIT_SHARDS_WHITELIST;

  static final String SET_SOLR_DISABLE_SHARDS_WHITELIST_CLUE = " set -D"+INIT_SOLR_DISABLE_SHARDS_WHITELIST+"=true to disable shards whitelist checks";

  /**
   * Get {@link ShardHandler} that uses the default http client.
   */
  @Override
  public ShardHandler getShardHandler() {
    return getShardHandler(defaultClient);
  }

  /**
   * Get {@link ShardHandler} that uses custom http client.
   */
  public ShardHandler getShardHandler(final HttpClient httpClient){
    return new HttpShardHandler(this, httpClient);
  }

  /**
   * Returns this Factory's {@link WhitelistHostChecker}.
   * This method can be overridden to change the checker implementation.
   */
  public WhitelistHostChecker getWhitelistHostChecker() {
    return this.whitelistHostChecker;
  }

  @Deprecated // For temporary use by the TermsComponent only.
  static boolean doGetDisableShardsWhitelist() {
    return getDisableShardsWhitelist();
  }


  private static boolean getDisableShardsWhitelist() {
    return Boolean.getBoolean(INIT_SOLR_DISABLE_SHARDS_WHITELIST);
  }

  @Override
  public void init(PluginInfo info) {
    StringBuilder sb = new StringBuilder();
    NamedList args = info.initArgs;
    this.soTimeout = getParameter(args, HttpClientUtil.PROP_SO_TIMEOUT, soTimeout,sb);
    this.scheme = getParameter(args, INIT_URL_SCHEME, null,sb);
    if(StringUtils.endsWith(this.scheme, "://")) {
      this.scheme = StringUtils.removeEnd(this.scheme, "://");
    }

    String strategy = getParameter(args, "metricNameStrategy", UpdateShardHandlerConfig.DEFAULT_METRICNAMESTRATEGY, sb);
    this.metricNameStrategy = KNOWN_METRIC_NAME_STRATEGIES.get(strategy);
    if (this.metricNameStrategy == null)  {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          "Unknown metricNameStrategy: " + strategy + " found. Must be one of: " + KNOWN_METRIC_NAME_STRATEGIES.keySet());
    }

    this.connectionTimeout = getParameter(args, HttpClientUtil.PROP_CONNECTION_TIMEOUT, connectionTimeout, sb);
    this.maxConnectionsPerHost = getParameter(args, HttpClientUtil.PROP_MAX_CONNECTIONS_PER_HOST, maxConnectionsPerHost,sb);
    this.maxConnections = getParameter(args, HttpClientUtil.PROP_MAX_CONNECTIONS, maxConnections,sb);
    this.corePoolSize = getParameter(args, INIT_CORE_POOL_SIZE, corePoolSize,sb);
    this.maximumPoolSize = getParameter(args, INIT_MAX_POOL_SIZE, maximumPoolSize,sb);
    this.keepAliveTime = getParameter(args, MAX_THREAD_IDLE_TIME, keepAliveTime,sb);
    this.queueSize = getParameter(args, INIT_SIZE_OF_QUEUE, queueSize,sb);
    this.accessPolicy = getParameter(args, INIT_FAIRNESS_POLICY, accessPolicy,sb);
    this.useRetries = getParameter(args, USE_RETRIES, useRetries,sb);
    this.connectionsEvictorSleepDelay = getParameter(args, CONNECTIONS_EVICTOR_SLEEP_DELAY, connectionsEvictorSleepDelay, sb);
    this.maxConnectionIdleTime = getParameter(args, MAX_CONNECTION_IDLE_TIME, maxConnectionIdleTime, sb);

    this.whitelistHostChecker = new WhitelistHostChecker(args == null? null: (String) args.get(INIT_SHARDS_WHITELIST), !getDisableShardsWhitelist());
    log.info("Host whitelist initialized: {}", this.whitelistHostChecker);
    
    log.debug("created with {}",sb);
    
    // magic sysprop to make tests reproducible: set by SolrTestCaseJ4.
    String v = System.getProperty("tests.shardhandler.randomSeed");
    if (v != null) {
      r.setSeed(Long.parseLong(v));
    }

    BlockingQueue<Runnable> blockingQueue = (this.queueSize == -1) ?
        new SynchronousQueue<Runnable>(this.accessPolicy) :
        new ArrayBlockingQueue<Runnable>(this.queueSize, this.accessPolicy);

    this.commExecutor = new ExecutorUtil.MDCAwareThreadPoolExecutor(
        this.corePoolSize,
        this.maximumPoolSize,
        this.keepAliveTime, TimeUnit.SECONDS,
        blockingQueue,
        new DefaultSolrThreadFactory("httpShardExecutor")
    );

    ModifiableSolrParams clientParams = getClientParams();
    clientConnectionManager = new InstrumentedPoolingClientConnectionManager(SchemeRegistryFactory.createSystemDefault());
    clientConnectionManager.setDefaultMaxPerRoute(maxConnectionsPerHost);
    clientConnectionManager.setMaxTotal(maxConnections);
    InstrumentedHttpClient httpClient = new InstrumentedHttpClient(clientConnectionManager, metricNameStrategy);
    HttpClientUtil.configureClient(httpClient, clientParams);
    this.defaultClient = httpClient;
    this.idleConnectionsEvictor = new UpdateShardHandler.IdleConnectionsEvictor(clientConnectionManager,
        connectionsEvictorSleepDelay, TimeUnit.MILLISECONDS, maxConnectionIdleTime, TimeUnit.MILLISECONDS);
    idleConnectionsEvictor.start();

    // must come after createClient
    if (useRetries) {
      // our default retry handler will never retry on IOException if the request has been sent already,
      // but for these read only requests we can use the standard DefaultHttpRequestRetryHandler rules
      ((DefaultHttpClient) this.defaultClient).setHttpRequestRetryHandler(new DefaultHttpRequestRetryHandler());
    }
    this.loadbalancer = createLoadbalancer(defaultClient);
  }

  protected ModifiableSolrParams getClientParams() {
    ModifiableSolrParams clientParams = new ModifiableSolrParams();
    clientParams.set(HttpClientUtil.PROP_SO_TIMEOUT, soTimeout);
    clientParams.set(HttpClientUtil.PROP_CONNECTION_TIMEOUT, connectionTimeout);
    if (!useRetries) {
      clientParams.set(HttpClientUtil.PROP_USE_RETRY, false);
    }
    return clientParams;
  }

  /**
   * For an already created internal httpclient, this can be used to configure it
   * again. Useful for authentication plugins.
   * @param configurer an HttpClientConfigurer instance
   */
  public void reconfigureHttpClient(HttpClientConfigurer configurer) {
    log.info("Reconfiguring the default client with: " + configurer);
    configurer.configure((DefaultHttpClient)this.defaultClient, getClientParams());
  }

  protected ExecutorService getThreadPoolExecutor() {
    return this.commExecutor;
  }

  protected LBHttpSolrClient createLoadbalancer(HttpClient httpClient){
    LBHttpSolrClient client = new Builder()
        .withHttpClient(httpClient)
        .build();
    return client;
  }

  protected <T> T getParameter(NamedList initArgs, String configKey, T defaultValue, StringBuilder sb) {
    T toReturn = defaultValue;
    if (initArgs != null) {
      T temp = (T) initArgs.get(configKey);
      toReturn = (temp != null) ? temp : defaultValue;
    }
    if(sb!=null && toReturn != null) sb.append(configKey).append(" : ").append(toReturn).append(",");
    return toReturn;
  }


  @Override
  public void close() {
    try {
      ExecutorUtil.shutdownAndAwaitTermination(commExecutor);
    } finally {
      try {
        if (idleConnectionsEvictor != null) {
          idleConnectionsEvictor.shutdown();
        }
        IOUtils.closeQuietly(defaultClient);
        if (clientConnectionManager != null)  {
          clientConnectionManager.shutdown();
        }
      } finally {

        if (loadbalancer != null) {
          loadbalancer.close();
        }
      }
    }
  }

  /**
   * Makes a request to one or more of the given urls, using the configured load balancer.
   *
   * @param req The solr search request that should be sent through the load balancer
   * @param urls The list of solr server urls to load balance across
   * @return The response from the request
   */
  public LBHttpSolrClient.Rsp makeLoadBalancedRequest(final QueryRequest req, List<String> urls)
    throws SolrServerException, IOException {
    return loadbalancer.request(new LBHttpSolrClient.Req(req, urls));
  }

  /**
   * Creates a list of urls for the given shard.
   *
   * @param shard the urls for the shard, separated by '|'
   * @return A list of valid urls (including protocol) that are replicas for the shard
   */
  public List<String> buildURLList(String shard) {
    List<String> urls = StrUtils.splitSmart(shard, "|", true);

    // convert shard to URL
    for (int i=0; i<urls.size(); i++) {
      urls.set(i, buildUrl(urls.get(i)));
    }

    return urls;
  }

  /**
   * A distributed request is made via {@link LBHttpSolrClient} to the first live server in the URL list.
   * This means it is just as likely to choose current host as any of the other hosts.
   * This function makes sure that the cores of current host are always put first in the URL list.
   * If all nodes prefer local-cores then a bad/heavily-loaded node will receive less requests from healthy nodes.
   * This will help prevent a distributed deadlock or timeouts in all the healthy nodes due to one bad node.
   */
  private static class IsOnPreferredHostComparator implements Comparator<Object> {
    final private String preferredHostAddress;
    public IsOnPreferredHostComparator(String preferredHostAddress) {
      this.preferredHostAddress = preferredHostAddress;
    }
    @Override
    public int compare(Object left, Object right) {
      final boolean lhs = hasPrefix(objectToString(left));
      final boolean rhs = hasPrefix(objectToString(right));
      if (lhs != rhs) {
        if (lhs) {
          return -1;
        } else {
          return +1;
        }
      } else {
        return 0;
      }
    }
    private String objectToString(Object o) {
      final String s;
      if (o instanceof String) {
        s = (String)o;
      }
      else if (o instanceof Replica) {
        s = ((Replica)o).getCoreUrl();
      } else {
        s = null;
      }
      return s;
    }
    private boolean hasPrefix(String s) {
      return s != null && s.startsWith(preferredHostAddress);
    }
  }
  protected ReplicaListTransformer getReplicaListTransformer(final SolrQueryRequest req)
  {
    final SolrParams params = req.getParams();

    if (params.getBool(CommonParams.PREFER_LOCAL_SHARDS, false)) {
      final CoreDescriptor coreDescriptor = req.getCore().getCoreDescriptor();
      final ZkController zkController = req.getCore().getCoreContainer().getZkController();
      final String preferredHostAddress = (zkController != null) ? zkController.getBaseUrl() : null;
      if (preferredHostAddress == null) {
        log.warn("Couldn't determine current host address to prefer local shards");
      } else {
        return new ShufflingReplicaListTransformer(r) {
          @Override
          public void transform(List<?> choices)
          {
            if (choices.size() > 1) {
              super.transform(choices);
              if (log.isDebugEnabled()) {
                log.debug("Trying to prefer local shard on {} among the choices: {}",
                    preferredHostAddress, Arrays.toString(choices.toArray()));
              }
              choices.sort(new IsOnPreferredHostComparator(preferredHostAddress));
              if (log.isDebugEnabled()) {
                log.debug("Applied local shard preference for choices: {}",
                    Arrays.toString(choices.toArray()));
              }
            }
          }
        };
      }
    }

    return shufflingReplicaListTransformer;
  }

  /**
   * Creates a new completion service for use by a single set of distributed requests.
   */
  public CompletionService newCompletionService() {
    return new ExecutorCompletionService<ShardResponse>(commExecutor);
  }
  
  /**
   * Rebuilds the URL replacing the URL scheme of the passed URL with the
   * configured scheme replacement.If no scheme was configured, the passed URL's
   * scheme is left alone.
   */
  private String buildUrl(String url) {
    if(!URLUtil.hasScheme(url)) {
      return StringUtils.defaultIfEmpty(scheme, DEFAULT_SCHEME) + "://" + url;
    } else if(StringUtils.isNotEmpty(scheme)) {
      return scheme + "://" + URLUtil.removeScheme(url);
    }
    
    return url;
  }

  @Override
  public void initializeMetrics(SolrMetricManager manager, String registry, String scope) {
    String expandedScope = SolrMetricManager.mkName(scope, SolrInfoMBean.Category.QUERY.name());
    clientConnectionManager.initializeMetrics(manager, registry, expandedScope);
    if (defaultClient instanceof SolrMetricProducer) {
      SolrMetricProducer solrMetricProducer = (SolrMetricProducer) defaultClient;
      solrMetricProducer.initializeMetrics(manager, registry, expandedScope);
    }
    commExecutor = MetricUtils.instrumentedExecutorService(commExecutor,
        manager.registry(registry),
        SolrMetricManager.mkName("httpShardExecutor", expandedScope, "threadPool"));
  }
  
  /**
   * Class used to validate the hosts in the "shards" parameter when doing a distributed
   * request
   */
  public static class WhitelistHostChecker {
    
    /**
     * List of the whitelisted hosts. Elements in the list will be host:port (no protocol or context)
     */
    private final Set<String> whitelistHosts;
    
    /**
     * Indicates whether host checking is enabled 
     */
    private final boolean whitelistHostCheckingEnabled;
    
    public WhitelistHostChecker(String whitelistStr, boolean enabled) {
      this.whitelistHosts = implGetShardsWhitelist(whitelistStr);
      this.whitelistHostCheckingEnabled = enabled;
    }
    
    final static Set<String> implGetShardsWhitelist(final String shardsWhitelist) {
      if (shardsWhitelist != null && !shardsWhitelist.isEmpty()) {
        return StrUtils.splitSmart(shardsWhitelist, ',')
            .stream()
            .map(String::trim)
            .map((hostUrl) -> {
              URL url;
              try {
                if (!hostUrl.startsWith("http://") && !hostUrl.startsWith("https://")) {
                  // It doesn't really matter which protocol we set here because we are not going to use it. We just need a full URL.
                  url = new URL("http://" + hostUrl);
                } else {
                  url = new URL(hostUrl);
                }
              } catch (MalformedURLException e) {
                throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Invalid URL syntax in \"" + INIT_SHARDS_WHITELIST + "\": " + shardsWhitelist, e);
              }
              if (url.getHost() == null || url.getPort() < 0) {
                throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Invalid URL syntax in \"" + INIT_SHARDS_WHITELIST + "\": " + shardsWhitelist);
              }
              return url.getHost() + ":" + url.getPort();
            }).collect(Collectors.toSet());
      }
      return null;
    }
    
    
    /**
     * @see #checkWhitelist(ClusterState, String, List)
     */
    protected void checkWhitelist(String shardsParamValue, List<String> shardUrls) {
      checkWhitelist(null, shardsParamValue, shardUrls);
    }
    
    /**
     * Checks that all the hosts for all the shards requested in shards parameter exist in the configured whitelist
     * or in the ClusterState (in case of cloud mode)
     * 
     * @param clusterState The up to date ClusterState, can be null in case of non-cloud mode
     * @param shardsParamValue The original shards parameter
     * @param shardUrls The list of cores generated from the shards parameter. 
     */
    protected void checkWhitelist(ClusterState clusterState, String shardsParamValue, List<String> shardUrls) {
      if (!whitelistHostCheckingEnabled) {
        return;
      }
      Set<String> localWhitelistHosts;
      if (whitelistHosts == null && clusterState != null) {
        // TODO: We could implement caching, based on the version of the live_nodes znode
        localWhitelistHosts = generateWhitelistFromLiveNodes(clusterState);
      } else if (whitelistHosts != null) {
        localWhitelistHosts = whitelistHosts;
      } else {
        localWhitelistHosts = Collections.emptySet();
      }
      
      shardUrls.stream().map(String::trim).forEach((shardUrl) -> {
        URL url;
        try {
          if (!shardUrl.startsWith("http://") && !shardUrl.startsWith("https://")) {
            // It doesn't really matter which protocol we set here because we are not going to use it. We just need a full URL.
            url = new URL("http://" + shardUrl);
          } else {
            url = new URL(shardUrl);
          }
        } catch (MalformedURLException e) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Invalid URL syntax in \"shards\" parameter: " + shardsParamValue, e);
        }
        if (url.getHost() == null || url.getPort() < 0) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Invalid URL syntax in \"shards\" parameter: " + shardsParamValue);
        }
        if (!localWhitelistHosts.contains(url.getHost() + ":" + url.getPort())) {
          log.warn("The '"+ShardParams.SHARDS+"' parameter value '"+shardsParamValue+"' contained value(s) not on the shards whitelist ("+localWhitelistHosts+"), shardUrl:" + shardUrl);
          throw new SolrException(ErrorCode.FORBIDDEN,
              "The '"+ShardParams.SHARDS+"' parameter value '"+shardsParamValue+"' contained value(s) not on the shards whitelist. shardUrl:" + shardUrl + "." +
                  HttpShardHandlerFactory.SET_SOLR_DISABLE_SHARDS_WHITELIST_CLUE);
        }
      });
    }
    
    Set<String> generateWhitelistFromLiveNodes(ClusterState clusterState) {
      return clusterState
          .getLiveNodes()
          .stream()
          .map((liveNode) -> liveNode.substring(0, liveNode.indexOf('_')))
          .collect(Collectors.toSet());
    }
    
    public boolean hasExplicitWhitelist() {
      return this.whitelistHosts != null;
    }
    
    public boolean isWhitelistHostCheckingEnabled() {
      return whitelistHostCheckingEnabled;
    }
    
    /**
     * Only to be used by tests
     */
    @VisibleForTesting
    Set<String> getWhitelistHosts() {
      return this.whitelistHosts;
    }

    @Override
    public String toString() {
      return "WhitelistHostChecker [whitelistHosts=" + whitelistHosts + ", whitelistHostCheckingEnabled="
          + whitelistHostCheckingEnabled + "]";
    }
    
  }
  
}
