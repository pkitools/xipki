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

package org.xipki.publisher.ocsp;

import java.util.Arrays;

import org.xipki.common.util.Base64;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

class IssuerEntry {

  private final int id;

  private final String subject;

  private final byte[] sha1Fp;

  private final byte[] cert;

  IssuerEntry(int id, String subject, String b64Sha1Fp, String b64Cert) {
    super();
    this.id = id;
    this.subject = subject;
    this.sha1Fp = Base64.decode(b64Sha1Fp);
    this.cert = Base64.decode(b64Cert);
  }

  int getId() {
    return id;
  }

  String getSubject() {
    return subject;
  }

  boolean matchSha1Fp(byte[] anotherSha1Fp) {
    return Arrays.equals(this.sha1Fp, anotherSha1Fp);
  }

  boolean matchCert(byte[] encodedCert) {
    return Arrays.equals(this.cert, encodedCert);
  }
}
