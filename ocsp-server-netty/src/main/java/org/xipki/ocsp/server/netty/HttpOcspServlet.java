/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ocsp.server.netty;

import java.io.EOFException;
import java.util.Date;

import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.common.util.Base64;
import org.xipki.common.util.LogUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.http.servlet.AbstractHttpServlet;
import org.xipki.http.servlet.ServletURI;
import org.xipki.http.servlet.SslReverseProxyMode;
import org.xipki.ocsp.api.OcspRespWithCacheInfo;
import org.xipki.ocsp.api.OcspServer;
import org.xipki.ocsp.api.Responder;
import org.xipki.ocsp.api.ResponderAndPath;
import org.xipki.security.HashAlgo;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class HttpOcspServlet extends AbstractHttpServlet {

  private static final Logger LOG = LoggerFactory.getLogger(HttpOcspServlet.class);

  private static final long DFLT_CACHE_MAX_AGE = 60; // 1 minute

  private static final String CT_REQUEST = "application/ocsp-request";

  private static final String CT_RESPONSE = "application/ocsp-response";

  private OcspServer server;

  public HttpOcspServlet() {
  }

  public void setServer(OcspServer server) {
    this.server = ParamUtil.requireNonNull("server", server);
  }

  @Override
  public FullHttpResponse service(FullHttpRequest request, ServletURI servletUri,
      SSLSession sslSession, SslReverseProxyMode sslReverseProxyMode) throws Exception {
    if (server == null) {
      String message = "responder in servlet not configured";
      LOG.error(message);
      return createErrorResponse(request.protocolVersion(),
          HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    HttpMethod method = request.method();
    if (HttpMethod.POST.equals(method)) {
      return servicePost(request, servletUri, sslSession, sslReverseProxyMode);
    } else if (HttpMethod.GET.equals(method)) {
      return serviceGet(request, servletUri, sslSession, sslReverseProxyMode);
    } else {
      return createErrorResponse(request.protocolVersion(),
          HttpResponseStatus.METHOD_NOT_ALLOWED);
    }

  }

  private FullHttpResponse servicePost(FullHttpRequest request, ServletURI servletUri,
      SSLSession sslSession, SslReverseProxyMode sslReverseProxyMode) throws Exception {
    HttpVersion version = request.protocolVersion();

    ResponderAndPath responderAndPath = server.getResponderForPath(servletUri.getPath());
    if (responderAndPath == null) {
      return createErrorResponse(version, HttpResponseStatus.NOT_FOUND);
    }

    try {
      // accept only "application/ocsp-request" as content type
      String reqContentType = request.headers().get("Content-Type");
      if (!CT_REQUEST.equalsIgnoreCase(reqContentType)) {
        return createErrorResponse(version, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE);
      }

      Responder responder = responderAndPath.getResponder();

      int contentLen = request.content().readableBytes();
      // request too long
      if (contentLen > responder.getMaxRequestSize()) {
        return createErrorResponse(version,
            HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
      }

      OcspRespWithCacheInfo ocspRespWithCacheInfo = server.answer(responder,
          readContent(request), false);
      if (ocspRespWithCacheInfo == null || ocspRespWithCacheInfo.getResponse() == null) {
        LOG.error("processRequest returned null, this should not happen");
        return createErrorResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }

      byte[] encodedOcspResp = ocspRespWithCacheInfo.getResponse();
      return createOKResponse(version, CT_RESPONSE, encodedOcspResp);
    } catch (Throwable th) {
      if (th instanceof EOFException) {
        LogUtil.warn(LOG, th, "Connection reset by peer");
      } else {
        LOG.error("Throwable thrown, this should not happen!", th);
      }
      return createErrorResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    } // end external try
  } // method servicePost

  private FullHttpResponse serviceGet(FullHttpRequest request, ServletURI servletUri,
      SSLSession sslSession, SslReverseProxyMode sslReverseProxyMode) throws Exception {
    HttpVersion version = request.protocolVersion();

    ResponderAndPath responderAndPath = server.getResponderForPath(servletUri.getPath());
    if (responderAndPath == null) {
      return createErrorResponse(version, HttpResponseStatus.NOT_FOUND);
    }

    String path = servletUri.getPath();
    String servletPath = responderAndPath.getServletPath();
    Responder responder = responderAndPath.getResponder();

    if (!responder.supportsHttpGet()) {
      return createErrorResponse(version, HttpResponseStatus.METHOD_NOT_ALLOWED);
    }

    String b64OcspReq;

    int offset = servletPath.length();
    // GET URI contains the request and must be much longer than 10.
    if (path.length() - offset > 10) {
      if (path.charAt(offset) == '/') {
        offset++;
      }
      b64OcspReq = servletUri.getPath().substring(offset);
    } else {
      return createErrorResponse(version, HttpResponseStatus.BAD_REQUEST);
    }

    try {
      // RFC2560 A.1.1 specifies that request longer than 255 bytes SHOULD be sent by
      // POST, we support GET for longer requests anyway.
      if (b64OcspReq.length() > responder.getMaxRequestSize()) {
        return createErrorResponse(version, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
      }

      OcspRespWithCacheInfo ocspRespWithCacheInfo = server.answer(responder,
          Base64.decode(b64OcspReq), true);
      if (ocspRespWithCacheInfo == null || ocspRespWithCacheInfo.getResponse() == null) {
        return createErrorResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }

      byte[] encodedOcspResp = ocspRespWithCacheInfo.getResponse();

      FullHttpResponse response = createOKResponse(version, CT_RESPONSE, encodedOcspResp);

      OcspRespWithCacheInfo.ResponseCacheInfo cacheInfo = ocspRespWithCacheInfo.getCacheInfo();
      if (cacheInfo != null) {
        encodedOcspResp = ocspRespWithCacheInfo.getResponse();
        HttpHeaders headers = response.headers();
        // RFC 5019 6.2: Date: The date and time at which the OCSP server generated
        // the HTTP response.
        headers.add("Date", new Date());
        // RFC 5019 6.2: Last-Modified: date and time at which the OCSP responder
        // last modified the response.
        headers.add("Last-Modified", new Date(cacheInfo.getThisUpdate()));
        // RFC 5019 6.2: Expires: This date and time will be the same as the
        // nextUpdate time-stamp in the OCSP
        // response itself.
        // This is overridden by max-age on HTTP/1.1 compatible components
        if (cacheInfo.getNextUpdate() != null) {
          headers.add("Expires", new Date(cacheInfo.getNextUpdate()));
        }
        // RFC 5019 6.2: This profile RECOMMENDS that the ETag value be the ASCII
        // HEX representation of the SHA1 hash of the OCSPResponse structure.
        headers.add("ETag", StringUtil.concat("\\", HashAlgo.SHA1.hexHash(encodedOcspResp), "\\"));

        // Max age must be in seconds in the cache-control header

        long maxAge;
        if (responder.getCacheMaxAge() != null) {
          maxAge = responder.getCacheMaxAge().longValue();
        } else {
          maxAge = DFLT_CACHE_MAX_AGE;
        }

        if (cacheInfo.getNextUpdate() != null) {
          maxAge = Math.min(maxAge, (cacheInfo.getNextUpdate() - cacheInfo.getThisUpdate()) / 1000);
        }

        headers.add("Cache-Control",
            StringUtil.concat("max-age=", Long.toString(maxAge),
                ",public,no-transform,must-revalidate"));
      } // end if (ocspRespWithCacheInfo)

      return response;
    } catch (Throwable th) {
      if (th instanceof EOFException) {
        LogUtil.warn(LOG, th, "Connection reset by peer");
      } else {
        LOG.error("Throwable thrown, this should not happen!", th);
      }
      return createErrorResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    } // end external try
  } // method serviceGet

}
