/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.federation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.Random;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.ha.HAServiceProtocol.HAServiceState;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.server.federation.resolver.ActiveNamenodeResolver;
import org.apache.hadoop.hdfs.server.federation.resolver.FederationNamenodeContext;
import org.apache.hadoop.hdfs.server.federation.resolver.FederationNamenodeServiceState;
import org.apache.hadoop.hdfs.server.federation.resolver.NamenodeStatusReport;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.security.AccessControlException;

/**
 * Helper utilities for testing HDFS Federation.
 */
public final class FederationTestUtils {

  public final static String[] NAMESERVICES = {"ns0", "ns1"};
  public final static String[] NAMENODES = {"nn0", "nn1", "nn2", "nn3"};
  public final static String[] ROUTERS =
      {"router0", "router1", "router2", "router3"};


  private FederationTestUtils() {
    // Utility class
  }

  public static void verifyException(Object obj, String methodName,
      Class<? extends Exception> exceptionClass, Class<?>[] parameterTypes,
      Object[] arguments) {

    Throwable triggeredException = null;
    try {
      Method m = obj.getClass().getMethod(methodName, parameterTypes);
      m.invoke(obj, arguments);
    } catch (InvocationTargetException ex) {
      triggeredException = ex.getTargetException();
    } catch (Exception e) {
      triggeredException = e;
    }
    if (exceptionClass != null) {
      assertNotNull("No exception was triggered, expected exception"
          + exceptionClass.getName(), triggeredException);
      assertEquals(exceptionClass, triggeredException.getClass());
    } else {
      assertNull("Exception was triggered but no exception was expected",
          triggeredException);
    }
  }

  public static NamenodeStatusReport createNamenodeReport(String ns, String nn,
      HAServiceState state) {
    Random rand = new Random();
    NamenodeStatusReport report = new NamenodeStatusReport(ns, nn,
        "localhost:" + rand.nextInt(10000), "localhost:" + rand.nextInt(10000),
        "localhost:" + rand.nextInt(10000), "testwebaddress-" + ns + nn);
    if (state == null) {
      // Unavailable, no additional info
      return report;
    }
    report.setHAServiceState(state);
    NamespaceInfo nsInfo = new NamespaceInfo(
        1, "tesclusterid", ns, 0, "testbuildvesion", "testsoftwareversion");
    report.setNamespaceInfo(nsInfo);
    return report;
  }

  public static void waitNamenodeRegistered(ActiveNamenodeResolver resolver,
      String nsId, String nnId, FederationNamenodeServiceState finalState)
          throws InterruptedException, IllegalStateException, IOException {

    for (int loopCount = 0; loopCount < 20; loopCount++) {
      if (loopCount > 0) {
        Thread.sleep(1000);
      }

      List<? extends FederationNamenodeContext> namenodes =
          resolver.getNamenodesForNameserviceId(nsId);
      for (FederationNamenodeContext namenode : namenodes) {
        // Check if this is the Namenode we are checking
        if (namenode.getNamenodeId() == nnId  ||
            namenode.getNamenodeId().equals(nnId)) {
          if (finalState != null && !namenode.getState().equals(finalState)) {
            // Wrong state, wait a bit more
            break;
          } else {
            // Found and verified
            return;
          }
        }
      }
    }
    fail("Failed to verify State Store registration of " + nsId + " " + nnId +
        " for state " + finalState);
  }

  public static boolean verifyDate(Date d1, Date d2, long precision) {
    return Math.abs(d1.getTime() - d2.getTime()) < precision;
  }

  public static <T> T getBean(String name, Class<T> obj)
      throws MalformedObjectNameException {
    MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
    ObjectName poolName = new ObjectName(name);
    return JMX.newMXBeanProxy(mBeanServer, poolName, obj);
  }

  public static boolean addDirectory(FileSystem context, String path)
      throws IOException {
    context.mkdirs(new Path(path), new FsPermission("777"));
    return verifyFileExists(context, path);
  }

  public static FileStatus getFileStatus(FileSystem context, String path)
      throws IOException {
    return context.getFileStatus(new Path(path));
  }

  public static boolean verifyFileExists(FileSystem context, String path) {
    try {
      FileStatus status = getFileStatus(context, path);
      if (status != null) {
        return true;
      }
    } catch (Exception e) {
      return false;
    }
    return false;
  }

  public static boolean checkForFileInDirectory(
      FileSystem context, String testPath, String targetFile)
          throws IOException, AccessControlException, FileNotFoundException,
          UnsupportedFileSystemException, IllegalArgumentException {

    FileStatus[] fileStatus = context.listStatus(new Path(testPath));
    String file = null;
    String verifyPath = testPath + "/" + targetFile;
    if (testPath.equals("/")) {
      verifyPath = testPath + targetFile;
    }

    Boolean found = false;
    for (int i = 0; i < fileStatus.length; i++) {
      FileStatus f = fileStatus[i];
      file = Path.getPathWithoutSchemeAndAuthority(f.getPath()).toString();
      if (file.equals(verifyPath)) {
        found = true;
      }
    }
    return found;
  }

  public static int countContents(FileSystem context, String testPath)
      throws IOException {
    Path path = new Path(testPath);
    FileStatus[] fileStatus = context.listStatus(path);
    return fileStatus.length;
  }

  public static void createFile(FileSystem fs, String path, long length)
      throws IOException {
    FsPermission permissions = new FsPermission("700");
    FSDataOutputStream writeStream = fs.create(new Path(path), permissions,
        true, 1000, (short) 1, DFSConfigKeys.DFS_BLOCK_SIZE_DEFAULT, null);
    for (int i = 0; i < length; i++) {
      writeStream.write(i);
    }
    writeStream.close();
  }

  public static String readFile(FileSystem fs, String path) throws IOException {
    // Read the file from the filesystem via the active namenode
    Path fileName = new Path(path);
    InputStreamReader reader = new InputStreamReader(fs.open(fileName));
    BufferedReader bufferedReader = new BufferedReader(reader);
    StringBuilder data = new StringBuilder();
    String line;

    while ((line = bufferedReader.readLine()) != null) {
      data.append(line);
    }

    bufferedReader.close();
    reader.close();
    return data.toString();
  }

  public static boolean deleteFile(FileSystem fs, String path)
      throws IOException {
    return fs.delete(new Path(path), true);
  }
}
