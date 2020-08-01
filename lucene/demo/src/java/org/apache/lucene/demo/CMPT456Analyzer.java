package org.apache.lucene.demo;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.standard.StandardFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.en.PorterStemFilter;

public final class CMPT456Analyzer extends StopwordAnalyzerBase {
    private static final int MAX_TOKEN_LENGTH = 255;
    private static final String STOP_WORD_FILE = "lucene/demo/src/java/org/apache/lucene/demo/stopwords.txt";
    private static CharArraySet CMPT456_STOP_WORDS;

    static {
        try {
            Path path = Paths.get(STOP_WORD_FILE);
            CMPT456_STOP_WORDS = loadStopwordSet(path);
        }
        catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        finally {
            if (CMPT456_STOP_WORDS == null) {
                CMPT456_STOP_WORDS = new CharArraySet(0, true);
            }
        }
    }

    public CMPT456Analyzer() {
        super(CMPT456_STOP_WORDS);
    }

    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        final StandardTokenizer src = new StandardTokenizer();
        src.setMaxTokenLength(MAX_TOKEN_LENGTH);
        
        TokenStream tok = new StandardFilter(src);
        tok = new LowerCaseFilter(tok);
        tok = new StopFilter(tok, stopwords);
        tok = new PorterStemFilter(tok);

        return new TokenStreamComponents(src, tok);
    }

    @Override
    protected TokenStream normalize(String fieldName, TokenStream in) {
        TokenStream result = new StandardFilter(in);
        result =  new LowerCaseFilter(result);    
        return new PorterStemFilter(result);
    }
}