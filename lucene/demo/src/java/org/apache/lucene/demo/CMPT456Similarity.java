package org.apache.lucene.demo;

import org.apache.lucene.search.similarities.ClassicSimilarity;

public class CMPT456Similarity extends ClassicSimilarity {
    public CMPT456Similarity() {
        super();
    }

    @Override
    public float idf(long docFreq, long docCount) {
        return (float)(1.0 + Math.log((docCount + 2) / (docFreq + 2)));
    }

    @Override
    public float tf(float freq) {
        return (float)Math.sqrt(1 + freq);
    }
}