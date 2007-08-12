/**
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

package org.apache.solr.request;

import java.io.BufferedReader;
import java.io.CharArrayReader;
import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;
import java.util.logging.Logger;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.util.xslt.TransformerProvider;

/** QueryResponseWriter which captures the output of the XMLWriter
 *  (in memory for now, not optimal performancewise), and applies an XSLT transform
 *  to it.
 */
public class XSLTResponseWriter implements QueryResponseWriter {

  public static final String DEFAULT_CONTENT_TYPE = "text/xml";
  public static final String TRANSFORM_PARAM = "tr";
  public static final String CONTEXT_TRANSFORMER_KEY = "xsltwriter.transformer";
  
  private Integer xsltCacheLifetimeSeconds = null; 
  public static final int XSLT_CACHE_DEFAULT = 60;
  private static final String XSLT_CACHE_PARAM = "xsltCacheLifetimeSeconds"; 

  private static final Logger log = Logger.getLogger(XSLTResponseWriter.class.getName());
  
  public void init(NamedList n) {
      final SolrParams p = SolrParams.toSolrParams(n);
      xsltCacheLifetimeSeconds = p.getInt(XSLT_CACHE_PARAM,XSLT_CACHE_DEFAULT);
      log.info("xsltCacheLifetimeSeconds=" + xsltCacheLifetimeSeconds);
  }

  
  public String getContentType(SolrQueryRequest request, SolrQueryResponse response) {
    Transformer t = null;
    try {
      t = getTransformer(request);
    } catch(Exception e) {
      // TODO should our parent interface throw (IO)Exception?
      throw new RuntimeException("getTransformer fails in getContentType",e);
    }
    
    final String mediaTypeFromXslt = t.getOutputProperty("media-type");
    if(mediaTypeFromXslt == null || mediaTypeFromXslt.length()==0) {
      // This did not happen in my tests, mediaTypeFromXslt is set to "text/xml"
      // if the XSLT transform does not contain an xsl:output element. Not sure
      // if this is standard behavior or if it's just my JVM/libraries
      return DEFAULT_CONTENT_TYPE;
    }
    return mediaTypeFromXslt;
  }

  public void write(Writer writer, SolrQueryRequest request, SolrQueryResponse response) throws IOException {
    final Transformer t = getTransformer(request);
    
    // capture the output of the XMLWriter
    final CharArrayWriter w = new CharArrayWriter();
    XMLWriter.writeResponse(w,request,response);
    
    // and write transformed result to our writer
    final Reader r = new BufferedReader(new CharArrayReader(w.toCharArray()));
    final StreamSource source = new StreamSource(r);
    final StreamResult result = new StreamResult(writer);
    try {
      t.transform(source, result);
    } catch(TransformerException te) {
      final IOException ioe = new IOException("XSLT transformation error");
      ioe.initCause(te);
      throw ioe;
    }
  }
  
  /** Get Transformer from request context, or from TransformerProvider.
   *  This allows either getContentType(...) or write(...) to instantiate the Transformer,
   *  depending on which one is called first, then the other one reuses the same Transformer
   */
  protected Transformer getTransformer(SolrQueryRequest request) throws IOException {
    final String xslt = request.getParams().get(TRANSFORM_PARAM,null);
    if(xslt==null) {
      throw new IOException("'" + TRANSFORM_PARAM + "' request parameter is required to use the XSLTResponseWriter");
    }
    
    // no need to synchronize access to context, right? 
    // Nothing else happens with it at the same time
    final Map<Object,Object> ctx = request.getContext();
    Transformer result = (Transformer)ctx.get(CONTEXT_TRANSFORMER_KEY);
    if(result==null) {
      result = TransformerProvider.instance.getTransformer(xslt,xsltCacheLifetimeSeconds.intValue());
      ctx.put(CONTEXT_TRANSFORMER_KEY,result);
    }
    return result;
  }
}
