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

package org.xipki.ca.dbtool.port.ca;

import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.cert.CRLException;
import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.stream.XMLStreamException;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.dbtool.jaxb.ca.CertstoreType;
import org.xipki.ca.dbtool.jaxb.ca.CertstoreType.DeltaCrlCache;
import org.xipki.ca.dbtool.jaxb.ca.CertstoreType.PublishQueue;
import org.xipki.ca.dbtool.jaxb.ca.DeltaCrlCacheEntryType;
import org.xipki.ca.dbtool.jaxb.ca.ObjectFactory;
import org.xipki.ca.dbtool.jaxb.ca.ToPublishType;
import org.xipki.ca.dbtool.port.DbPortFileNameIterator;
import org.xipki.ca.dbtool.port.DbPorter;
import org.xipki.ca.dbtool.xmlio.DbiXmlReader;
import org.xipki.ca.dbtool.xmlio.IdentifidDbObjectType;
import org.xipki.ca.dbtool.xmlio.InvalidDataObjectException;
import org.xipki.ca.dbtool.xmlio.ca.CertType;
import org.xipki.ca.dbtool.xmlio.ca.CertsReader;
import org.xipki.ca.dbtool.xmlio.ca.CrlType;
import org.xipki.ca.dbtool.xmlio.ca.CrlsReader;
import org.xipki.ca.dbtool.xmlio.ca.RequestCertType;
import org.xipki.ca.dbtool.xmlio.ca.RequestCertsReader;
import org.xipki.ca.dbtool.xmlio.ca.RequestType;
import org.xipki.ca.dbtool.xmlio.ca.RequestsReader;
import org.xipki.common.ProcessLog;
import org.xipki.common.util.Base64;
import org.xipki.common.util.IoUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.common.util.XmlUtil;
import org.xipki.datasource.DataAccessException;
import org.xipki.datasource.DataSourceWrapper;
import org.xipki.security.FpIdCalculator;
import org.xipki.security.HashAlgo;
import org.xipki.security.util.X509Util;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

class CaCertstoreDbImporter extends AbstractCaCertstoreDbPorter {

  private static final Logger LOG = LoggerFactory.getLogger(CaCertstoreDbImporter.class);

  private static final String SQL_ADD_CERT =
      "INSERT INTO CERT (ID,LUPDATE,SN,SUBJECT,FP_S,FP_RS,NBEFORE,NAFTER,REV,RR,RT,RIT,"
      + "PID,CA_ID,RID,UID,FP_K,EE,RTYPE,TID) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String SQL_ADD_CRAW =
      "INSERT INTO CRAW (CID,SHA1,REQ_SUBJECT,CERT) VALUES (?,?,?,?)";

  private static final String SQL_ADD_CRL =
      "INSERT INTO CRL (ID,CA_ID,CRL_NO,THISUPDATE,NEXTUPDATE,DELTACRL,BASECRL_NO,CRL)"
      + " VALUES (?,?,?,?,?,?,?,?)";

  private static final String SQL_ADD_REQUEST =
      "INSERT INTO REQUEST (ID,LUPDATE,DATA) VALUES (?,?,?)";

  private static final String SQL_ADD_REQCERT =
      "INSERT INTO REQCERT (ID,RID,CID) VALUES (?,?,?)";

  private final Unmarshaller unmarshaller;

  private final boolean resume;

  private final int numCertsPerCommit;

  CaCertstoreDbImporter(DataSourceWrapper datasource, String srcDir, int numCertsPerCommit,
      boolean resume, AtomicBoolean stopMe, boolean evaluateOnly) throws Exception {
    super(datasource, srcDir, stopMe, evaluateOnly);

    this.numCertsPerCommit = ParamUtil.requireMin("numCertsPerCommit", numCertsPerCommit, 1);
    this.resume = resume;

    File processLogFile = new File(baseDir, DbPorter.IMPORT_PROCESS_LOG_FILENAME);
    if (resume) {
      if (!processLogFile.exists()) {
        throw new Exception("could not process with '--resume' option");
      }
    } else {
      if (processLogFile.exists()) {
        throw new Exception("please either specify '--resume' option or delete the file "
            + processLogFile.getPath() + " first");
      }
    }

    JAXBContext jaxbContext = JAXBContext.newInstance(ObjectFactory.class);
    unmarshaller = jaxbContext.createUnmarshaller();
    unmarshaller.setSchema(DbPorter.retrieveSchema("/xsd/dbi-ca.xsd"));
  }

  public void importToDb() throws Exception {
    CertstoreType certstore;
    try {
      @SuppressWarnings("unchecked")
      JAXBElement<CertstoreType> root = (JAXBElement<CertstoreType>)
          unmarshaller.unmarshal(new File(baseDir, FILENAME_CA_CERTSTORE));
      certstore = root.getValue();
    } catch (JAXBException ex) {
      throw XmlUtil.convert(ex);
    }

    if (certstore.getVersion() > VERSION) {
      throw new Exception("could not import Certstore greater than " + VERSION + ": "
          + certstore.getVersion());
    }

    File processLogFile = new File(baseDir, DbPorter.IMPORT_PROCESS_LOG_FILENAME);
    System.out.println("importing CA certstore to database");
    try {
      if (!resume) {
        dropIndexes();
      }

      CaDbEntryType typeProcessedInLastProcess = null;
      Integer numProcessedInLastProcess = null;
      Long idProcessedInLastProcess = null;
      if (processLogFile.exists()) {
        byte[] content = IoUtil.read(processLogFile);
        if (content != null && content.length > 5) {
          String str = new String(content);
          StringTokenizer st = new StringTokenizer(str, ":");
          String type = st.nextToken();
          typeProcessedInLastProcess = CaDbEntryType.valueOf(type);
          numProcessedInLastProcess = Integer.parseInt(st.nextToken());
          idProcessedInLastProcess = Long.parseLong(st.nextToken());
        }
      }

      boolean entriesFinished = false;
      // finished for the given type
      if (typeProcessedInLastProcess != null && (idProcessedInLastProcess != null
          && idProcessedInLastProcess == -1)) {
        numProcessedInLastProcess = 0;
        idProcessedInLastProcess = 0L;

        switch (typeProcessedInLastProcess) {
          case CRL:
            typeProcessedInLastProcess = CaDbEntryType.CERT;
            break;
          case CERT:
            typeProcessedInLastProcess = CaDbEntryType.REQUEST;
            break;
          case REQUEST:
            typeProcessedInLastProcess = CaDbEntryType.REQCERT;
            break;
          case REQCERT:
            entriesFinished = true;
            break;
          default:
            throw new RuntimeException("unsupported CaDbEntryType " + typeProcessedInLastProcess);
        }
      }

      if (!entriesFinished) {
        Exception exception = null;
        if (CaDbEntryType.CRL == typeProcessedInLastProcess
            || typeProcessedInLastProcess == null) {
          exception = importEntries(CaDbEntryType.CRL, certstore, processLogFile,
              numProcessedInLastProcess, idProcessedInLastProcess);
          typeProcessedInLastProcess = null;
          numProcessedInLastProcess = null;
          idProcessedInLastProcess = null;
        }

        CaDbEntryType[] types = {CaDbEntryType.CERT, CaDbEntryType.REQUEST, CaDbEntryType.REQCERT};

        for (CaDbEntryType type : types) {
          if (exception == null
              && (type == typeProcessedInLastProcess || typeProcessedInLastProcess == null)) {
            exception = importEntries(type, certstore, processLogFile,
                numProcessedInLastProcess, idProcessedInLastProcess);
          }
        }

        if (exception != null) {
          throw exception;
        }
      }

      importPublishQueue(certstore.getPublishQueue());
      importDeltaCrlCache(certstore.getDeltaCrlCache());

      recoverIndexes();
      processLogFile.delete();
    } catch (Exception ex) {
      System.err.println("could not import CA certstore to database");
      throw ex;
    }
    System.out.println(" imported CA certstore to database");
  } // method importToDb

  private void importPublishQueue(PublishQueue publishQueue) throws DataAccessException {
    final String sql = "INSERT INTO PUBLISHQUEUE (CID,PID,CA_ID) VALUES (?,?,?)";
    System.out.println("importing table PUBLISHQUEUE");
    PreparedStatement ps = prepareStatement(sql);

    try {
      for (ToPublishType tbp : publishQueue.getTop()) {
        try {
          ps.setLong(1, tbp.getCertId());
          ps.setInt(2, tbp.getPubId());
          ps.setInt(3, tbp.getCaId());
          ps.execute();
        } catch (SQLException ex) {
          System.err.println("could not import PUBLISHQUEUE with CID="
              + tbp.getCertId() + " and PID=" + tbp.getPubId() + ", message: " + ex.getMessage());
          throw translate(sql, ex);
        }
      }
    } finally {
      releaseResources(ps, null);
    }

    System.out.println(" imported table PUBLISHQUEUE");
  } // method importPublishQueue

  private void importDeltaCrlCache(DeltaCrlCache deltaCrlCache) throws DataAccessException {
    final String sql = "INSERT INTO DELTACRL_CACHE (ID,SN,CA_ID) VALUES (?,?,?)";
    System.out.println("importing table DELTACRL_CACHE");
    PreparedStatement ps = prepareStatement(sql);

    try {
      long id = 1;
      for (DeltaCrlCacheEntryType entry : deltaCrlCache.getEntry()) {
        try {
          ps.setLong(1, id++);
          ps.setString(2, entry.getSerial());
          ps.setInt(3, entry.getCaId());
          ps.execute();
        } catch (SQLException ex) {
          System.err.println("could not import DELTACRL_CACHE with caId=" + entry.getCaId()
              + " and serial=" + entry.getSerial() + ", message: " + ex.getMessage());
          throw translate(sql, ex);
        }
      }
    } finally {
      releaseResources(ps, null);
    }

    System.out.println(" imported table DELTACRL_CACHE");
  } // method importDeltaCRLCache

  private Exception importEntries(CaDbEntryType type, CertstoreType certstore,
      File processLogFile, Integer numProcessedInLastProcess, Long idProcessedInLastProcess) {
    String tablesText = (CaDbEntryType.CERT == type)
        ? "tables CERT and CRAW" : "table " + type.getTableName();

    try {
      int numProcessedBefore = 0;
      long minId = 1;
      if (idProcessedInLastProcess != null) {
        minId = idProcessedInLastProcess + 1;
        numProcessedBefore = numProcessedInLastProcess;
      }

      deleteFromTableWithLargerId(type.getTableName(), "ID", minId - 1, LOG);
      if (type == CaDbEntryType.CERT) {
        deleteFromTableWithLargerId("CRAW", "CID", minId - 1, LOG);
      }

      final long total;
      String[] sqls;

      switch (type) {
        case CERT:
          total = certstore.getCountCerts();
          sqls = new String[] {SQL_ADD_CERT, SQL_ADD_CRAW};
          break;
        case CRL:
          total = certstore.getCountCrls();
          sqls = new String[] {SQL_ADD_CRL};
          break;
        case REQUEST:
          total = certstore.getCountRequests();
          sqls = new String[] {SQL_ADD_REQUEST};
          break;
        case REQCERT:
          total = certstore.getCountReqCerts();
          sqls = new String[] {SQL_ADD_REQCERT};
          break;
        default:
          throw new RuntimeException("unsupported DbEntryType " + type);
      }

      final long remainingTotal = total - numProcessedBefore;
      final ProcessLog processLog = new ProcessLog(remainingTotal);

      System.out.println(importingText() + "entries to " + tablesText + " from ID " + minId);
      processLog.printHeader();

      DbPortFileNameIterator entriesFileIterator = null;
      PreparedStatement[] statements = null;

      try {
        entriesFileIterator = new DbPortFileNameIterator(
            baseDir + File.separator + type.getDirName() + ".mf");

        statements = new PreparedStatement[sqls.length];
        for (int i = 0; i < sqls.length; i++) {
          statements[i] = prepareStatement(sqls[i]);
        }

        while (entriesFileIterator.hasNext()) {
          String entriesFile = baseDir + File.separator + type.getDirName()
              + File.separator + entriesFileIterator.next();

          // extract the toId from the filename
          int fromIdx = entriesFile.indexOf('-');
          int toIdx = entriesFile.indexOf(".zip");
          if (fromIdx != -1 && toIdx != -1) {
            try {
              long toId = Integer.parseInt(entriesFile.substring(fromIdx + 1, toIdx));
              if (toId < minId) {
                // try next file
                continue;
              }
            } catch (Exception ex) {
              LOG.warn("invalid file name '{}', but will still be processed", entriesFile);
            }
          } else {
            LOG.warn("invalid file name '{}', but will still be processed", entriesFile);
          }

          try {
            long lastId = importEntries(type, entriesFile, minId, processLogFile,
                processLog, numProcessedBefore, statements, sqls);
            minId = lastId + 1;
          } catch (Exception ex) {
            System.err.println("\ncould not import entries from file "
                + entriesFile + ".\nplease continue with the option '--resume'");
            LOG.error("Exception", ex);
            return ex;
          }
        } // end for
      } finally {
        if (statements != null) {
          for (PreparedStatement stmt : statements) {
            if (stmt != null) {
              releaseResources(stmt, null);
            }
          }
        }
        if (entriesFileIterator != null) {
          entriesFileIterator.close();
        }
      }

      processLog.printTrailer();
      echoToFile(type + ":" + (numProcessedBefore + processLog.numProcessed()) + ":-1",
          processLogFile);

      System.out.println(importedText() + processLog.numProcessed() + " entries");
      return null;
    } catch (Exception ex) {
      System.err.println("\nimporting " + tablesText + " has been cancelled due to error,\n"
          + "please continue with the option '--resume'");
      LOG.error("Exception", ex);
      return ex;
    }
  }

  private long importEntries(CaDbEntryType type, String entriesZipFile, long minId,
      File processLogFile, ProcessLog processLog, int numProcessedInLastProcess,
      PreparedStatement[] statements, String[] sqls) throws Exception {
    final int numEntriesPerCommit = Math.max(1,
        Math.round(type.getSqlBatchFactor() * numCertsPerCommit));

    ZipFile zipFile = new ZipFile(new File(entriesZipFile));
    ZipEntry entriesXmlEntry = zipFile.getEntry("overview.xml");

    DbiXmlReader entries;
    try {
      entries = createReader(type, zipFile.getInputStream(entriesXmlEntry));
    } catch (Exception ex) {
      try {
        zipFile.close();
      } catch (Exception e2) {
        LOG.error("could not close ZIP file {}: {}", entriesZipFile, e2.getMessage());
        LOG.debug("could not close ZIP file " + entriesZipFile, e2);
      }
      throw ex;
    }

    disableAutoCommit();

    try {
      int numEntriesInBatch = 0;
      long lastSuccessfulEntryId = 0;

      while (entries.hasNext()) {
        if (stopMe.get()) {
          throw new InterruptedException("interrupted by the user");
        }

        IdentifidDbObjectType entry = (IdentifidDbObjectType) entries.next();
        long id = entry.getId();
        if (id < minId) {
          continue;
        }

        numEntriesInBatch++;

        if (CaDbEntryType.CERT == type) {
          CertType cert = (CertType) entry;

          String filename = cert.getFile();
          // rawcert
          ZipEntry certZipEnty = zipFile.getEntry(filename);
          // rawcert
          byte[] encodedCert = IoUtil.read(zipFile.getInputStream(certZipEnty));

          TBSCertificate tbsCert;
          try {
            Certificate cc = Certificate.getInstance(encodedCert);
            tbsCert = cc.getTBSCertificate();
          } catch (RuntimeException ex) {
            LOG.error("could not parse certificate in file {}", filename);
            LOG.debug("could not parse certificate in file " + filename, ex);
            throw new CertificateException(ex.getMessage(), ex);
          }

          byte[] encodedKey = tbsCert.getSubjectPublicKeyInfo().getPublicKeyData().getBytes();

          String b64Sha1FpCert = HashAlgo.SHA1.base64Hash(encodedCert);

          // cert
          String subjectText = X509Util.cutX500Name(tbsCert.getSubject(), maxX500nameLen);

          PreparedStatement psCert = statements[0];
          PreparedStatement psRawcert = statements[1];

          try {
            int idx = 1;

            psCert.setLong(idx++, id);
            psCert.setLong(idx++, cert.getUpdate());
            psCert.setString(idx++, tbsCert.getSerialNumber().getPositiveValue().toString(16));

            psCert.setString(idx++, subjectText);
            long fpSubject = X509Util.fpCanonicalizedName(tbsCert.getSubject());
            psCert.setLong(idx++, fpSubject);

            if (cert.getFpRs() != null) {
              psCert.setLong(idx++, cert.getFpRs());
            } else {
              psCert.setNull(idx++, Types.BIGINT);
            }

            psCert.setLong(idx++, tbsCert.getStartDate().getDate().getTime() / 1000);
            psCert.setLong(idx++, tbsCert.getEndDate().getDate().getTime() / 1000);
            setBoolean(psCert, idx++, cert.getRev());
            setInt(psCert, idx++, cert.getRr());
            setLong(psCert, idx++, cert.getRt());
            setLong(psCert, idx++, cert.getRit());
            setInt(psCert, idx++, cert.getPid());
            setInt(psCert, idx++, cert.getCaId());

            setInt(psCert, idx++, cert.getRid());
            setInt(psCert, idx++, cert.getUid());
            psCert.setLong(idx++, FpIdCalculator.hash(encodedKey));
            Extension extension = tbsCert.getExtensions().getExtension(Extension.basicConstraints);
            boolean ee = true;
            if (extension != null) {
              ASN1Encodable asn1 = extension.getParsedValue();
              ee = !BasicConstraints.getInstance(asn1).isCA();
            }

            psCert.setInt(idx++, ee ? 1 : 0);
            psCert.setInt(idx++, cert.getReqType());
            String tidS = null;
            if (cert.getTid() != null) {
              tidS = cert.getTid();
            }
            psCert.setString(idx++, tidS);
            psCert.addBatch();
          } catch (SQLException ex) {
            throw translate(SQL_ADD_CERT, ex);
          }

          try {
            int idx = 1;
            psRawcert.setLong(idx++, cert.getId());
            psRawcert.setString(idx++, b64Sha1FpCert);
            psRawcert.setString(idx++, cert.getRs());
            psRawcert.setString(idx++, Base64.encodeToString(encodedCert));
            psRawcert.addBatch();
          } catch (SQLException ex) {
            throw translate(SQL_ADD_CRAW, ex);
          }
        } else if (CaDbEntryType.CRL == type) {
          PreparedStatement psAddCrl = statements[0];

          CrlType crl = (CrlType) entry;

          String filename = crl.getFile();

          // CRL
          ZipEntry zipEnty = zipFile.getEntry(filename);

          // rawcert
          byte[] encodedCrl = IoUtil.read(zipFile.getInputStream(zipEnty));

          X509CRL x509crl = null;
          try {
            x509crl = X509Util.parseCrl(encodedCrl);
          } catch (Exception ex) {
            LOG.error("could not parse CRL in file {}", filename);
            LOG.debug("could not parse CRL in file " + filename, ex);
            if (ex instanceof CRLException) {
              throw (CRLException) ex;
            } else {
              throw new CRLException(ex.getMessage(), ex);
            }
          }

          try {
            byte[] octetString = x509crl.getExtensionValue(Extension.cRLNumber.getId());
            if (octetString == null) {
              LOG.warn("CRL without CRL number, ignore it");
              continue;
            }
            byte[] extnValue = DEROctetString.getInstance(octetString).getOctets();
            // CHECKSTYLE:SKIP
            BigInteger crlNumber = ASN1Integer.getInstance(extnValue).getPositiveValue();

            BigInteger baseCrlNumber = null;
            octetString = x509crl.getExtensionValue(Extension.deltaCRLIndicator.getId());
            if (octetString != null) {
              extnValue = DEROctetString.getInstance(octetString).getOctets();
              baseCrlNumber = ASN1Integer.getInstance(extnValue).getPositiveValue();
            }

            int idx = 1;
            psAddCrl.setLong(idx++, crl.getId());
            psAddCrl.setInt(idx++, crl.getCaId());
            psAddCrl.setLong(idx++, crlNumber.longValue());
            psAddCrl.setLong(idx++, x509crl.getThisUpdate().getTime() / 1000);
            if (x509crl.getNextUpdate() != null) {
              psAddCrl.setLong(idx++, x509crl.getNextUpdate().getTime() / 1000);
            } else {
              psAddCrl.setNull(idx++, Types.INTEGER);
            }

            if (baseCrlNumber == null) {
              setBoolean(psAddCrl, idx++, false);
              psAddCrl.setNull(idx++, Types.BIGINT);
            } else {
              setBoolean(psAddCrl, idx++, true);
              psAddCrl.setLong(idx++, baseCrlNumber.longValue());
            }

            String str = Base64.encodeToString(encodedCrl);
            psAddCrl.setString(idx++, str);

            psAddCrl.addBatch();
          } catch (SQLException ex) {
            System.err.println("could not import CRL with ID=" + crl.getId()
                + ", message: " + ex.getMessage());
            throw ex;
          }
        } else if (CaDbEntryType.REQUEST == type) {
          PreparedStatement psAddRequest = statements[0];

          RequestType request = (RequestType) entry;

          String filename = request.getFile();

          ZipEntry zipEnty = zipFile.getEntry(filename);
          byte[] encodedRequest = IoUtil.read(zipFile.getInputStream(zipEnty));

          try {
            int idx = 1;
            psAddRequest.setLong(idx++, request.getId());
            psAddRequest.setLong(idx++, request.getUpdate());
            psAddRequest.setString(idx++, Base64.encodeToString(encodedRequest));
            psAddRequest.addBatch();
          } catch (SQLException ex) {
            System.err.println("could not import REQUEST with ID=" + request.getId()
                + ", message: " + ex.getMessage());
            throw ex;
          }
        } else if (CaDbEntryType.REQCERT == type) {
          PreparedStatement psAddReqCert = statements[0];

          RequestCertType reqCert = (RequestCertType) entry;

          try {
            int idx = 1;
            psAddReqCert.setLong(idx++, reqCert.getId());
            psAddReqCert.setLong(idx++, reqCert.getRid());
            psAddReqCert.setLong(idx++, reqCert.getCid());
            psAddReqCert.addBatch();
          } catch (SQLException ex) {
            System.err.println("could not import REQUEST with ID=" + reqCert.getId()
                + ", message: " + ex.getMessage());
            throw ex;
          }
        } else {
          throw new RuntimeException("Unknown CaDbEntryType " + type);
        }

        boolean isLastBlock = !entries.hasNext();
        if (numEntriesInBatch > 0
            && (numEntriesInBatch % numEntriesPerCommit == 0 || isLastBlock)) {
          if (evaulateOnly) {
            for (PreparedStatement m : statements) {
              m.clearBatch();
            }
          } else {
            String sql = null;

            try {
              for (int i = 0; i < sqls.length; i++) {
                sql = sqls[i];
                statements[i].executeBatch();
              }

              sql = null;
              commit("(commit import to CA)");
            } catch (Throwable th) {
              rollback();
              deleteFromTableWithLargerId(type.getTableName(), "ID", id, LOG);
              if (CaDbEntryType.CERT == type) {
                deleteFromTableWithLargerId("CRAW", "CID", id, LOG);
              }
              if (th instanceof SQLException) {
                throw translate(sql, (SQLException) th);
              } else if (th instanceof Exception) {
                throw (Exception) th;
              } else {
                throw new Exception(th);
              }
            }
          }

          lastSuccessfulEntryId = id;
          processLog.addNumProcessed(numEntriesInBatch);
          numEntriesInBatch = 0;
          echoToFile(type + ":" + (numProcessedInLastProcess + processLog.numProcessed()) + ":"
              + lastSuccessfulEntryId, processLogFile);
          processLog.printStatus();
        }

      } // end while

      return lastSuccessfulEntryId;
    } finally {
      recoverAutoCommit();
      zipFile.close();
    }
  } // method importEntries

  private static DbiXmlReader createReader(CaDbEntryType type, InputStream is)
      throws XMLStreamException, InvalidDataObjectException {
    switch (type) {
      case CERT:
        return new CertsReader(is);
      case CRL:
        return new CrlsReader(is);
      case REQUEST:
        return new RequestsReader(is);
      case REQCERT:
        return new RequestCertsReader(is);
      default:
        throw new RuntimeException("unknown CaDbEntryType " + type);
    }
  }

  private void dropIndexes() throws DataAccessException {
    long start = System.currentTimeMillis();

    datasource.dropIndex(null, "CERT", "IDX_CA_FPK");
    datasource.dropIndex(null, "CERT", "IDX_CA_FPS");
    datasource.dropIndex(null, "CERT", "IDX_CA_FPRS");

    datasource.dropForeignKeyConstraint(null, "FK_CERT_CA1", "CERT");
    datasource.dropForeignKeyConstraint(null, "FK_CERT_USER1", "CERT");

    datasource.dropUniqueConstrain(null, "CONST_CA_SN", "CERT");

    datasource.dropForeignKeyConstraint(null, "FK_CRAW_CERT1", "CRAW");
    datasource.dropForeignKeyConstraint(null, "FK_PUBLISHQUEUE_CERT1", "PUBLISHQUEUE");

    datasource.dropForeignKeyConstraint(null, "FK_REQCERT_REQ1", "REQCERT");
    datasource.dropForeignKeyConstraint(null, "FK_REQCERT_CERT1", "REQCERT");

    datasource.dropPrimaryKey(null, "PK_CERT", "CERT");
    datasource.dropPrimaryKey(null, "PK_CRAW", "CRAW");
    datasource.dropPrimaryKey(null, "PK_REQUEST", "REQUEST");
    datasource.dropPrimaryKey(null, "PK_REQCERT", "REQCERT");

    long duration = (System.currentTimeMillis() - start) / 1000;
    System.out.println(" dropped indexes in " + StringUtil.formatTime(duration, false));
  }

  private void recoverIndexes() throws DataAccessException {
    long start = System.currentTimeMillis();
    datasource.addPrimaryKey(null, "PK_CERT", "CERT", "ID");
    datasource.addPrimaryKey(null, "PK_CRAW", "CRAW", "CID");
    datasource.addPrimaryKey(null, "PK_REQUEST", "REQUEST", "ID");
    datasource.addPrimaryKey(null, "PK_REQCERT", "REQCERT", "ID");

    datasource.addForeignKeyConstraint(null, "FK_PUBLISHQUEUE_CERT1", "PUBLISHQUEUE",
        "CID", "CERT", "ID", "CASCADE", "NO ACTION");

    datasource.addForeignKeyConstraint(null, "FK_CRAW_CERT1", "CRAW",
        "CID", "CERT", "ID", "CASCADE", "NO ACTION");

    datasource.addForeignKeyConstraint(null, "FK_CERT_CA1", "CERT",
        "CA_ID", "CA", "ID", "CASCADE", "NO ACTION");

    datasource.addForeignKeyConstraint(null, "FK_CERT_USER1", "CERT",
        "UID", "TUSER", "ID", "CASCADE", "NO ACTION");

    datasource.addForeignKeyConstraint(null, "FK_REQCERT_REQ1", "REQCERT",
        "RID", "REQUEST", "ID", "CASCADE", "NO ACTION");

    datasource.addForeignKeyConstraint(null, "FK_REQCERT_CERT1", "REQCERT",
        "CID", "CERT", "ID", "CASCADE", "NO ACTION");

    datasource.addUniqueConstrain(null, "CONST_CA_SN", "CERT", "CA_ID", "SN");

    datasource.createIndex(null, "IDX_CA_FPK", "CERT", "CA_ID", "FP_K");
    datasource.createIndex(null, "IDX_CA_FPS", "CERT", "CA_ID", "FP_S");
    datasource.createIndex(null, "IDX_CA_FPRS", "CERT", "CA_ID", "FP_RS");

    long duration = (System.currentTimeMillis() - start) / 1000;
    System.out.println(" recovered indexes in " + StringUtil.formatTime(duration, false));
  }

}
