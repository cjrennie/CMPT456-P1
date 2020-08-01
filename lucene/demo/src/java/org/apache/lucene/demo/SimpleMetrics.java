package org.apache.lucene.demo;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StandardDirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.SimpleFSDirectory;

public class SimpleMetrics {
    private static final Path INDEX_DIR = Paths.get("/lucene-solr/index/");
    private static final String TERMINATING_STR = "!";
    private static final Scanner SCANNER = new Scanner(System.in);
    private static final StandardAnalyzer ANALYZER = new StandardAnalyzer();
    private static final ClassicSimilarity SIMILAR = new ClassicSimilarity();
    // private static final CMPT456Analyzer ANALYZER = new CMPT456Analyzer();
    // private static final ClassicSimilarity SIMILAR = new CMPT456Similarity();

    public static void main(String[] args) {
        try {
            DirectoryReader dirReader = StandardDirectoryReader.open(new SimpleFSDirectory(INDEX_DIR));
       
            System.out.println("Enter a term to show metrics for or '!' to exit");
            String input = SCANNER.nextLine().trim();
            
            while (input.compareTo(TERMINATING_STR) != 0) {
                Term contentTerm = new Term("contents", ANALYZER.normalize(null, input)); // need to stem input

                int docFreq = dirReader.docFreq(contentTerm);
                long termFreq = dirReader.totalTermFreq(contentTerm);
                
                System.out.println("Metrics for: " + input + " normalized = " + contentTerm.text());
                System.out.println("    term frequency = " + termFreq);
                System.out.println("    doc. frequency = " + docFreq);
                System.out.println("    tf score       = " + SIMILAR.tf(termFreq));
                System.out.println("    idf score      = " + SIMILAR.idf(docFreq, dirReader.getDocCount("contents")));

                input = SCANNER.nextLine().trim();
            }
        }
        catch (IOException ioe) {
            ioe.printStackTrace();
            System.exit(1);
        }
    }
}