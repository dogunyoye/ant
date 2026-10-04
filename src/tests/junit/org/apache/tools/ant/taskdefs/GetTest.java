/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.tools.ant.taskdefs;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.BuildFileRule;
import org.apache.tools.ant.Project;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.Transient;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 */
public class GetTest {

    @Rule
    public final BuildFileRule buildRule = new BuildFileRule();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void setUp() {
        buildRule.configureProject("src/etc/testcases/taskdefs/get.xml");
    }

    @After
    public void tearDown() {
        buildRule.executeTarget("cleanup");
    }

    /**
     * Fail due to missing required argument
     */
    @Test(expected = BuildException.class)
    public void test1() {
        buildRule.executeTarget("test1");
        // TODO assert value
    }

    /**
     * Fail due to missing required argument
     */
    @Test(expected = BuildException.class)
    public void test2() {
        buildRule.executeTarget("test2");
        // TODO assert value
    }

    /**
     * Fail due to missing required argument
     */
    @Test(expected = BuildException.class)
    public void test3() {
        buildRule.executeTarget("test3");
        // TODO assert value
    }

    /**
     * Fail due to invalid src argument
     */
    @Test(expected = BuildException.class)
    public void test4() {
        buildRule.executeTarget("test4");
        // TODO assert value
    }

    /**
     * Fail due to invalid dest argument or no HTTP server on localhost
     */
    @Test(expected = BuildException.class)
    public void test5() {
        buildRule.executeTarget("test5");
        // TODO assert value
    }

    @Test
    public void test6() {
        buildRule.executeTarget("test6");
    }

    /**
     * Fail due to null or empty userAgent argument
     */
    @Test
    public void test7() {
        thrown.expect(BuildException.class);
        try {
            buildRule.executeTarget("test7");
        } finally {
            // post-mortem
            assertThat(buildRule.getLog(), not(containsString("Adding header")));
        }
    }

    @Test
    public void testBasicAuth() {
        buildRule.executeTarget("testBasicAuth");
    }

    @Test
    public void testDigestAuth() {
        buildRule.executeTarget("testDigestAuth");
    }

    @Test
    public void testUseTimestamp() {
        buildRule.executeTarget("testUseTimestamp");
    }

    @Test
    public void testUseTomorrow() {
        buildRule.executeTarget("testUseTomorrow");
    }

    @Test
    public void testTwoHeadersAreAddedOK() {
        buildRule.executeTarget("testTwoHeadersAreAddedOK");
        assertThat(buildRule.getLog(), both(containsString("Adding header 'header1'"))
                        .and(containsString("Adding header 'header2'")));
    }

    @Test
    public void testEmptyHeadersAreNeverAdded() {
        buildRule.executeTarget("testEmptyHeadersAreNeverAdded");
        assertThat(buildRule.getLog(), not(containsString("Adding header")));
    }

    @Test
    public void testThatWhenMoreThanOneHeaderHaveSameNameOnlyLastOneIsAdded() {
        buildRule.executeTarget("testThatWhenMoreThanOneHeaderHaveSameNameOnlyLastOneIsAdded");
        String log = buildRule.getLog();
        assertThat(log, containsString("Adding header 'header1'"));

        int actualHeaderCount = log.split("Adding header ").length - 1;

        assertEquals("Only one header has been added", 1, actualHeaderCount);
    }

    @Test
    public void testHeaderSpaceTrimmed() {
        buildRule.executeTarget("testHeaderSpaceTrimmed");
        assertThat(buildRule.getLog(), containsString("Adding header 'header1'"));
    }

    @Test
    public void testExplicitCredentialsWinOverUrlUserInfo() throws Exception {
        URL url = new URL("http://ignored:ignored@localhost/");
        assertEquals("user:pass",
            Get.getBasicAuthCredentials(url, "user", "pass"));
    }

    @Test
    public void testNullUsernameTreatedAsEmpty() throws Exception {
        URL url = new URL("http://localhost/");
        assertEquals(":secret", Get.getBasicAuthCredentials(url, null, "secret"));
        assertEquals("user:", Get.getBasicAuthCredentials(url, "user", null));
    }

    @Test
    public void testUrlUserInfoFallback() throws Exception {
        assertEquals("foo:bar", Get.getBasicAuthCredentials(
            new URL("http://foo:bar@localhost/"), null, null));
        assertEquals("foo:", Get.getBasicAuthCredentials(
            new URL("http://foo@localhost/"), null, null));
    }

    @Test
    public void testNoCredentialsYieldsNull() throws Exception {
        assertNull(Get.getBasicAuthCredentials(new URL("http://localhost/"), null, null));
    }

    @Test
    public void testEncodeBasicAuthUsesIso88591() {
        String expected = Base64.getEncoder()
            .encodeToString("foo:bar".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(expected, Get.encodeBasicAuth("foo:bar"));
        assertNull(Get.encodeBasicAuth(null));
    }

    @Test
    public void testSplitUserInfo() {
        assertNull(Get.splitUserInfo(null));
        org.junit.Assert.assertArrayEquals(new String[] {"foo", "bar"},
            Get.splitUserInfo("foo:bar"));
        org.junit.Assert.assertArrayEquals(new String[] {"foo", ""},
            Get.splitUserInfo("foo"));
        // password containing a colon stays intact
        org.junit.Assert.assertArrayEquals(new String[] {"foo", "a:b"},
            Get.splitUserInfo("foo:a:b"));
    }

    @Test
    public void testParseDigestChallenge() {
        assertNull(Get.parseDigestChallenge(null));
        assertNull(Get.parseDigestChallenge("Basic realm=\"test\""));
        Map<String, String> params = Get.parseDigestChallenge(
            "Digest realm=\"testrealm@host.com\", "
            + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", "
            + "qop=\"auth, auth-int\", algorithm=MD5, "
            + "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
        assertEquals("testrealm@host.com", params.get("realm"));
        assertEquals("dcd98b7102dd2f0e8b11d0f600bfb0c093",
            params.get("nonce"));
        assertEquals("auth, auth-int", params.get("qop"));
        assertEquals("MD5", params.get("algorithm"));
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41",
            params.get("opaque"));
    }

    @Test
    public void testSelectDigestChallenge() {
        assertNull(Get.selectDigestChallenge(null));
        assertNull(Get.selectDigestChallenge(
            Collections.singletonList("Basic realm=\"test\"")));
        assertNull(Get.selectDigestChallenge(Collections.emptyList()));
        String digest = "Digest realm=\"test\", nonce=\"abc\"";
        assertEquals(digest, Get.selectDigestChallenge(
            Arrays.asList("Basic realm=\"test\"", digest)));
    }

    @Test
    public void testGetDigestUri() throws Exception {
        assertEquals("/dir/index.html", Get.getDigestUri(
            new URL("http://localhost/dir/index.html")));
        assertEquals("/search?q=ant", Get.getDigestUri(
            new URL("http://localhost/search?q=ant")));
        assertEquals("/", Get.getDigestUri(new URL("http://localhost")));
    }

    @Test
    public void testRfc2617DigestResponseVector() {
        // Worked example from RFC 2617, section 3.5
        Map<String, String> challenge = new LinkedHashMap<>();
        challenge.put("realm", "testrealm@host.com");
        challenge.put("nonce", "dcd98b7102dd2f0e8b11d0f600bfb0c093");
        challenge.put("qop", "auth");
        challenge.put("opaque", "5ccc069c403ebaf9f0171e9517f40e41");
        String auth = Get.buildDigestAuthorization("Mufasa",
            "Circle Of Life", "GET", "/dir/index.html", challenge,
            "00000001", "0a4f113b");
        assertThat(auth,
            containsString("response=\"6629fae49393a05397450978507c4ef1\""));
        assertThat(auth, containsString("username=\"Mufasa\""));
        assertThat(auth, containsString("qop=auth"));
        assertThat(auth, containsString("nc=00000001"));
        assertThat(auth, containsString("cnonce=\"0a4f113b\""));
    }

    @Test
    public void testBuildDigestAuthorizationEdgeCases() {
        Map<String, String> challenge = new LinkedHashMap<>();
        challenge.put("realm", "test");
        challenge.put("nonce", "abc123");
        // legacy mode without qop: no qop/nc/cnonce parameters
        String auth = Get.buildDigestAuthorization("user", "pass", "GET",
            "/", challenge, "00000001", "cnoncevalue");
        assertThat(auth, containsString("response=\""));
        assertThat(auth, not(containsString("qop=")));
        assertThat(auth, not(containsString("nc=")));
        // unsupported algorithm cannot be answered
        Map<String, String> badAlg = new LinkedHashMap<>(challenge);
        badAlg.put("algorithm", "FUTURE-HASH");
        assertNull(Get.buildDigestAuthorization("user", "pass", "GET",
            "/", badAlg, "00000001", "cnoncevalue"));
        // missing realm/nonce cannot be answered
        assertNull(Get.buildDigestAuthorization("user", "pass", "GET",
            "/", new LinkedHashMap<>(), "00000001", "cnoncevalue"));
        assertNull(Get.buildDigestAuthorization(null, "pass", "GET",
            "/", challenge, "00000001", "cnoncevalue"));
    }

    @Test
    public void testDigestAuthAgainstLocalServer() throws Exception {
        final String realm = "ant-test";
        final String nonce = "testnonce123";
        final String challenge = "Digest realm=\"" + realm + "\", nonce=\""
            + nonce + "\", qop=\"auth\", algorithm=MD5, opaque=\"opaque123\"";
        com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/digest", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst(
                "Authorization");
            byte[] body;
            boolean ok = false;
            if (auth != null && auth.startsWith("Digest ")) {
                Map<String, String> params =
                    Get.parseDigestChallenge(auth);
                if (params != null
                    && "foo".equals(params.get("username"))
                    && nonce.equals(params.get("nonce"))
                    && exchange.getRequestURI().getPath()
                        .equals(params.get("uri"))) {
                    String ha1 = md5hex("foo:" + realm + ":bar");
                    String ha2 = md5hex(exchange.getRequestMethod()
                        + ":" + params.get("uri"));
                    String expected = md5hex(ha1 + ":" + params.get("nonce")
                        + ":" + params.get("nc") + ":"
                        + params.get("cnonce") + ":" + params.get("qop")
                        + ":" + ha2);
                    ok = expected.equals(params.get("response"));
                }
            }
            if (ok) {
                body = ("{\"authenticated\": true,"
                    + " \"scheme\": \"digest\"}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
            } else {
                body = "unauthorized".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add(
                    "WWW-Authenticate", challenge);
                exchange.sendResponseHeaders(401, body.length);
            }
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();

            // correct credentials negotiate Digest automatically
            File dest1 = File.createTempFile("ant-get-digest1", ".tmp");
            dest1.deleteOnExit();
            Project p1 = new Project();
            p1.init();
            Get get1 = new Get();
            get1.setProject(p1);
            get1.setSrc(new URL("http://127.0.0.1:" + port + "/digest"));
            get1.setDest(dest1);
            get1.setUsername("foo");
            get1.setPassword("bar");
            get1.execute();
            assertTrue(new String(Files.readAllBytes(dest1.toPath()),
                StandardCharsets.UTF_8).contains("\"scheme\": \"digest\""));

            // wrong password must fail, not hang or throw NPE
            File dest2 = File.createTempFile("ant-get-digest2", ".tmp");
            dest2.deleteOnExit();
            Project p2 = new Project();
            p2.init();
            Get get2 = new Get();
            get2.setProject(p2);
            get2.setSrc(new URL("http://127.0.0.1:" + port + "/digest"));
            get2.setDest(dest2);
            get2.setUsername("foo");
            get2.setPassword("wrong");
            try {
                get2.execute();
                fail("expected BuildException for bad digest credentials");
            } catch (BuildException e) {
                assertThat(e.getMessage(),
                    containsString("HTTP Authorization failure"));
            }
        } finally {
            server.stop(0);
        }
    }

    private static String md5hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest =
                md.digest(text.getBytes(StandardCharsets.ISO_8859_1));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("MD5 not available", e);
        }
    }

    @Test
    public void testBasicAuthAgainstLocalServer() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
            .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        final String expected = "Basic " + Base64.getEncoder().encodeToString(
            "foo:bar".getBytes(StandardCharsets.ISO_8859_1));
        server.createContext("/protected", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body;
            if (expected.equals(auth)) {
                body = "{\"authenticated\": true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
            } else {
                body = "unauthorized".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"test\"");
                exchange.sendResponseHeaders(401, body.length);
            }
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/no-challenge", exchange -> {
            byte[] body = "unauthorized".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();

            // 1. explicit username/password attributes
            File dest1 = File.createTempFile("ant-get-auth1", ".tmp");
            dest1.deleteOnExit();
            Project p1 = new Project();
            p1.init();
            Get get1 = new Get();
            get1.setProject(p1);
            get1.setSrc(new URL("http://127.0.0.1:" + port + "/protected"));
            get1.setDest(dest1);
            get1.setUsername("foo");
            get1.setPassword("bar");
            get1.execute();
            assertTrue(new String(Files.readAllBytes(dest1.toPath()),
                StandardCharsets.UTF_8).contains("authenticated"));

            // 2. credentials embedded in URL userInfo (no explicit attributes)
            File dest2 = File.createTempFile("ant-get-auth2", ".tmp");
            dest2.deleteOnExit();
            Project p2 = new Project();
            p2.init();
            Get get2 = new Get();
            get2.setProject(p2);
            get2.setSrc(new URL("http://foo:bar@127.0.0.1:" + port + "/protected"));
            get2.setDest(dest2);
            get2.execute();
            assertTrue(new String(Files.readAllBytes(dest2.toPath()),
                StandardCharsets.UTF_8).contains("authenticated"));

            // 3. wrong credentials must fail with authorization message, not NPE
            File dest3 = File.createTempFile("ant-get-auth3", ".tmp");
            dest3.deleteOnExit();
            Project p3 = new Project();
            p3.init();
            Get get3 = new Get();
            get3.setProject(p3);
            get3.setSrc(new URL("http://127.0.0.1:" + port + "/protected"));
            get3.setDest(dest3);
            get3.setUsername("foo");
            get3.setPassword("wrong");
            try {
                get3.execute();
                fail("expected BuildException for bad credentials");
            } catch (BuildException e) {
                assertThat(e.getMessage(), containsString("HTTP Authorization failure"));
            }

            // 4. 401 without WWW-Authenticate header must not throw NPE
            File dest4 = File.createTempFile("ant-get-auth4", ".tmp");
            dest4.deleteOnExit();
            Project p4 = new Project();
            p4.init();
            Get get4 = new Get();
            get4.setProject(p4);
            get4.setSrc(new URL("http://127.0.0.1:" + port + "/no-challenge"));
            get4.setDest(dest4);
            try {
                get4.execute();
                fail("expected BuildException for 401 without challenge");
            } catch (BuildException e) {
                assertThat(e.getMessage(), containsString("HTTP Authorization failure"));
            }
        } finally {
            server.stop(0);
        }
    }
}
