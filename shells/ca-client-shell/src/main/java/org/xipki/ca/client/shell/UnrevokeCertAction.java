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

package org.xipki.ca.client.shell;

import java.security.cert.X509Certificate;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.xipki.ca.client.api.CertIdOrError;
import org.xipki.cmp.PkiStatusInfo;
import org.xipki.common.RequestResponseDebug;
import org.xipki.console.karaf.CmdFailure;
import org.xipki.console.karaf.IllegalCmdParamException;
import org.xipki.security.util.X509Util;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

@Command(scope = "xi", name = "cmp-unrevoke",
    description = "unrevoke certificate")
@Service
public class UnrevokeCertAction extends UnRevRemoveCertAction {

  @Override
  protected Object execute0() throws Exception {
    if (!(certFile == null ^ getSerialNumber() == null)) {
      throw new IllegalCmdParamException("exactly one of cert and serial must be specified");
    }

    CertIdOrError certIdOrError;
    if (certFile != null) {
      X509Certificate cert = X509Util.parseCert(certFile);
      RequestResponseDebug debug = getRequestResponseDebug();
      try {
        certIdOrError = caClient.unrevokeCert(caName, cert, debug);
      } finally {
        saveRequestResponse(debug);
      }
    } else {
      RequestResponseDebug debug = getRequestResponseDebug();
      try {
        certIdOrError = caClient.unrevokeCert(caName, getSerialNumber(), debug);
      } finally {
        saveRequestResponse(debug);
      }
    }

    if (certIdOrError.getError() != null) {
      PkiStatusInfo error = certIdOrError.getError();
      throw new CmdFailure("releasing revocation failed: " + error);
    } else {
      println("unrevoked certificate");
    }
    return null;
  } // method execute0

}
