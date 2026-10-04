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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.MagicNames;
import org.apache.tools.ant.Main;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.email.Header;
import org.apache.tools.ant.types.Mapper;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.ResourceCollection;
import org.apache.tools.ant.types.resources.Resources;
import org.apache.tools.ant.types.resources.URLProvider;
import org.apache.tools.ant.types.resources.URLResource;
import org.apache.tools.ant.util.FileNameMapper;
import org.apache.tools.ant.util.FileUtils;
import org.apache.tools.ant.util.StringUtils;

/**
 * Gets a particular file from a URL source.
 * Options include verbose reporting, timestamp based fetches and controlling
 * actions on failures. NB: access through a firewall only works if the whole
 * Java runtime is correctly configured.
 *
 * @since Ant 1.1
 *
 * @ant.task category="network"
 */
public class Get extends Task {
    private static final int NUMBER_RETRIES = 3;
    private static final int DOTS_PER_LINE = 50;
    private static final int BIG_BUFFER_SIZE = 100 * 1024;
    private static final FileUtils FILE_UTILS = FileUtils.getFileUtils();
    private static final int REDIRECT_LIMIT = 25;
    // HttpURLConnection doesn't have a constant for this in Java5 and
    // what it calls HTTP_MOVED_TEMP would better be FOUND
    private static final int HTTP_MOVED_TEMP = 307;

    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    private static final String DEFAULT_AGENT_PREFIX = "Apache Ant";
    private static final String GZIP_CONTENT_ENCODING = "gzip";

    private final Resources sources = new Resources();
    private File destination; // required
    private boolean verbose = false;
    private boolean quiet = false;
    private boolean useTimestamp = false; //off by default
    private boolean ignoreErrors = false;
    private String uname = null;
    private String pword = null;
    private boolean authenticateOnRedirect = false;
    private long maxTime = 0;
    private int numberRetries = NUMBER_RETRIES;
    private boolean skipExisting = false;
    private boolean httpUseCaches = true; // on by default
    private boolean tryGzipEncoding = false;
    private Mapper mapperElement = null;
    private String userAgent =
        System.getProperty(MagicNames.HTTP_AGENT_PROPERTY,
                           DEFAULT_AGENT_PREFIX + "/"
                           + Main.getShortAntVersion());

    // Store headers as key/value pair without duplicate in keys
    private Map<String, String> headers = new LinkedHashMap<>();

    /**
     * Does the work.
     *
     * @exception BuildException Thrown in unrecoverable error.
     */
    @Override
    public void execute() throws BuildException {
        checkAttributes();

        for (final Resource r : sources) {
            final URLProvider up = r.as(URLProvider.class);
            final URL source = up.getURL();

            File dest = destination;
            if (destination.isDirectory()) {
                if (mapperElement == null) {
                    String path = source.getPath();
                    if (path.endsWith("/")) {
                        path = path.substring(0, path.length() - 1);
                    }
                    final int slash = path.lastIndexOf('/');
                    if (slash > -1) {
                        path = path.substring(slash + 1);
                    }
                    dest = new File(destination, path);
                } else {
                    final FileNameMapper mapper = mapperElement.getImplementation();
                    final String[] d = mapper.mapFileName(source.toString());
                    if (d == null) {
                        log("skipping " + r + " - mapper can't handle it",
                            Project.MSG_WARN);
                        continue;
                    }
                    if (d.length == 0) {
                        log("skipping " + r + " - mapper returns no file name",
                            Project.MSG_WARN);
                        continue;
                    }
                    if (d.length > 1) {
                        log("skipping " + r + " - mapper returns multiple file"
                            + " names", Project.MSG_WARN);
                        continue;
                    }
                    dest = new File(destination, d[0]);
                }
            }

            //set up logging
            final int logLevel = Project.MSG_INFO;
            DownloadProgress progress = null;
            if (verbose) {
                progress = new VerboseProgress(System.out);
            }

            //execute the get
            try {
                doGet(source, dest, logLevel, progress);
            } catch (final IOException ioe) {
                log("Error getting " + source + " to " + dest);
                if (!ignoreErrors) {
                    throw new BuildException(ioe, getLocation());
                }
            }
        }
    }

    /**
     * make a get request, with the supplied progress and logging info.
     * All the other config parameters are set at the task level,
     * source, dest, ignoreErrors, etc.
     * @param logLevel level to log at, see {@link Project#log(String, int)}
     * @param progress progress callback; null for no-callbacks
     * @return true for a successful download, false otherwise.
     * The return value is only relevant when {@link #ignoreErrors} is true, as
     * when false all failures raise BuildExceptions.
     * @throws IOException for network trouble
     * @throws BuildException for argument errors, or other trouble when ignoreErrors
     * is false.
     * @deprecated only gets the first configured resource
     */
    @Deprecated
    public boolean doGet(final int logLevel, final DownloadProgress progress)
            throws IOException {
        checkAttributes();
        return doGet(sources.iterator().next().as(URLProvider.class).getURL(),
                destination, logLevel, progress);

    }

    /**
     * make a get request, with the supplied progress and logging info.
     *
     * All the other config parameters like ignoreErrors are set at
     * the task level.
     * @param source the URL to get
     * @param dest the target file
     * @param logLevel level to log at, see {@link Project#log(String, int)}
     * @param progress progress callback; null for no-callbacks
     * @return true for a successful download, false otherwise.
     * The return value is only relevant when {@link #ignoreErrors} is true, as
     * when false all failures raise BuildExceptions.
     * @throws IOException for network trouble
     * @throws BuildException for argument errors, or other trouble when ignoreErrors
     * is false.
     * @since Ant 1.8.0
     */
    public boolean doGet(final URL source, final File dest, final int logLevel,
                         DownloadProgress progress)
        throws IOException {

        if (dest.exists() && skipExisting) {
            log("Destination already exists (skipping): "
                + dest.getAbsolutePath(), logLevel);
            return true;
        }

        // don't do any progress, unless asked
        if (progress == null) {
            progress = new NullProgress();
        }
        log("Getting: " + source, logLevel);
        log("To: " + dest.getAbsolutePath(), logLevel);

        //set the timestamp to the file date.
        long timestamp = 0;

        boolean hasTimestamp = false;
        if (useTimestamp && dest.exists()) {
            timestamp = dest.lastModified();
            if (verbose) {
                final Date t = new Date(timestamp);
                log("local file date : " + t.toString(), logLevel);
            }
            hasTimestamp = true;
        }

        final GetThread getThread = new GetThread(source, dest,
                                            hasTimestamp, timestamp, progress,
                                            logLevel, userAgent);
        getThread.setDaemon(true);
        getProject().registerThreadTask(getThread, this);
        getThread.start();
        try {
            getThread.join(maxTime * 1000);
        } catch (final InterruptedException ie) {
            log("interrupted waiting for GET to finish",
                Project.MSG_VERBOSE);
        }

        if (getThread.isAlive()) {
            final String msg = "The GET operation took longer than " + maxTime
                + " seconds, stopping it.";
            if (ignoreErrors) {
                log(msg);
            }
            getThread.closeStreams();
            if (!ignoreErrors) {
                throw new BuildException(msg);
            }
            return false;
        }

        return getThread.wasSuccessful();
    }

    @Override
    public void log(final String msg, final int msgLevel) {
        if (!quiet || msgLevel <= Project.MSG_ERR) {
            super.log(msg, msgLevel);
        }
    }

    /**
     * Check the attributes.
     */
    private void checkAttributes() {

        if (userAgent == null || userAgent.trim().isEmpty()) {
            throw new BuildException("userAgent may not be null or empty");
        }

        if (sources.size() == 0) {
            throw new BuildException("at least one source is required",
                                     getLocation());
        }
        for (final Resource r : sources) {
            final URLProvider up = r.as(URLProvider.class);
            if (up == null) {
                throw new BuildException(
                    "Only URLProvider resources are supported", getLocation());
            }
        }

        if (destination == null) {
            throw new BuildException("dest attribute is required", getLocation());
        }

        if (destination.exists() && sources.size() > 1
            && !destination.isDirectory()) {
            throw new BuildException(
                "The specified destination is not a directory", getLocation());
        }

        if (destination.exists() && !destination.canWrite()) {
            throw new BuildException("Can't write to "
                                     + destination.getAbsolutePath(),
                                     getLocation());
        }

        if (sources.size() > 1 && !destination.exists()) {
            destination.mkdirs();
        }
    }

    /**
     * Set an URL to get.
     *
     * @param u URL for the file.
     */
    public void setSrc(final URL u) {
        add(new URLResource(u));
    }

    /**
     * Adds URLs to get.
     * @param rc ResourceCollection
     * @since Ant 1.8.0
     */
    public void add(final ResourceCollection rc) {
        sources.add(rc);
    }

    /**
     * Where to copy the source file.
     *
     * @param dest Path to file.
     */
    public void setDest(final File dest) {
        this.destination = dest;
    }

    /**
     * If true, show verbose progress information.
     *
     * @param v if "true" then be verbose
     */
    public void setVerbose(final boolean v) {
        verbose = v;
    }

    /**
     * If true, set default log level to Project.MSG_ERR.
     *
     * @param v if "true" then be quiet
     * @since Ant 1.9.4
     */
    public void setQuiet(final boolean v) {
        this.quiet = v;
    }

    /**
     * If true, log errors but do not treat as fatal.
     *
     * @param v if "true" then don't report download errors up to ant
     */
    public void setIgnoreErrors(final boolean v) {
        ignoreErrors = v;
    }

    /**
     * If true, conditionally download a file based on the timestamp
     * of the local copy.
     *
     * <p>In this situation, the if-modified-since header is set so
     * that the file is only fetched if it is newer than the local
     * file (or there is no local file) This flag is only valid on
     * HTTP connections, it is ignored in other cases.  When the flag
     * is set, the local copy of the downloaded file will also have
     * its timestamp set to the remote file time.</p>
     *
     * <p>Note that remote files of date 1/1/1970 (GMT) are treated as
     * 'no timestamp', and web servers often serve files with a
     * timestamp in the future by replacing their timestamp with that
     * of the current time. Also, inter-computer clock differences can
     * cause no end of grief.</p>
     * @param v "true" to enable file time fetching
     */
    public void setUseTimestamp(final boolean v) {
        useTimestamp = v;
    }

    /**
     * Username for HTTP authentication (Basic or Digest).
     *
     * @param u username for authentication
     */
    public void setUsername(final String u) {
        this.uname = u;
    }

    /**
     * Password for HTTP authentication (Basic or Digest).
     *
     * @param p password for authentication
     */
    public void setPassword(final String p) {
        this.pword = p;
    }

    /**
     * If true, credentials are set when following a redirect to a new location.
     *
     * @param v "true" to enable sending the credentials on redirect; "false" otherwise
     * @since Ant 1.10.13
     */
    public void setAuthenticateOnRedirect(final boolean v) {
        this.authenticateOnRedirect = v;
    }

    /**
     * The time in seconds the download is allowed to take before
     * being terminated.
     *
     * @param maxTime long
     * @since Ant 1.8.0
     */
    public void setMaxTime(final long maxTime) {
        this.maxTime = maxTime;
    }

    /**
     * The number of attempts to make for opening the URI, defaults to 3.
     *
     * <p>The name of the method is misleading as a value of 1 means
     * "don't retry on error" and a value of 0 meant don't even try to
     * reach the URI at all.</p>
     *
     * @param r number of attempts to make
     * @since Ant 1.8.0
     */
    public void setRetries(final int r) {
        if (r <= 0) {
            log("Setting retries to " + r
                + " will make the task not even try to reach the URI at all",
                Project.MSG_WARN);
        }
        this.numberRetries = r;
    }

    /**
     * Skip files that already exist locally.
     *
     * @param s "true" to skip existing destination files
     * @since Ant 1.8.0
     */
    public void setSkipExisting(final boolean s) {
        this.skipExisting = s;
    }

    /**
     * HTTP connections only - set the user-agent to be used
     * when communicating with remote server. if null, then
     * the value is considered unset and the behaviour falls
     * back to the default of the http API.
     *
     * @param userAgent String
     * @since Ant 1.9.3
     */
    public void setUserAgent(final String userAgent) {
        this.userAgent = userAgent;
    }

    /**
     * HTTP connections only - control caching on the
     * HttpUrlConnection: httpConnection.setUseCaches(); if false, do
     * not allow caching on the HttpUrlConnection.
     *
     * <p>Defaults to true (allow caching, which is also the
     * HttpUrlConnection default value.</p>
     *
     * @param httpUseCache boolean
     * @since Ant 1.8.0
     */
    public void setHttpUseCaches(final boolean httpUseCache) {
        this.httpUseCaches = httpUseCache;
    }

    /**
     * Whether to transparently try to reduce bandwidth by telling the
     * server ant would support gzip encoding.
     *
     * <p>Setting this to true also means Ant will uncompress
     * <code>.tar.gz</code> and similar files automatically.</p>
     *
     * @param b boolean
     * @since Ant 1.9.5
     */
    public void setTryGzipEncoding(boolean b) {
        tryGzipEncoding = b;
    }

    /**
     * Add a nested header
     * @param header to be added
     *
     */
    public void addConfiguredHeader(Header header) {
        if (header != null) {
            String key = StringUtils.trimToNull(header.getName());
            String value = StringUtils.trimToNull(header.getValue());
            if (key != null && value != null) {
                this.headers.put(key, value);
            }
        }
    }

    /**
     * Define the mapper to map source to destination files.
     * @return a mapper to be configured.
     * @exception BuildException if more than one mapper is defined.
     * @since Ant 1.8.0
     */
    public Mapper createMapper() throws BuildException {
        if (mapperElement != null) {
            throw new BuildException("Cannot define more than one mapper",
                                     getLocation());
        }
        mapperElement = new Mapper(getProject());
        return mapperElement;
    }

    /**
     * Add a nested filenamemapper.
     * @param fileNameMapper the mapper to add.
     * @since Ant 1.8.0
     */
    public void add(final FileNameMapper fileNameMapper) {
        createMapper().add(fileNameMapper);
    }

    /**
     * Provide this for Backward Compatibility.
     */
    protected static class Base64Converter
        extends org.apache.tools.ant.util.Base64Converter {
    }

    /**
     * Does the response code represent a redirection?
     *
     * @since 1.10.10
     */
    public static boolean isMoved(final int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM
            || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
            || responseCode == HttpURLConnection.HTTP_SEE_OTHER
            || responseCode == HTTP_MOVED_TEMP;
    }

    /**
     * Split a URL userInfo part (as returned by {@link URL#getUserInfo()})
     * into username and password.
     *
     * @param userInfo the raw userInfo, may be null
     * @return a two element array with username and password (never null
     *         elements), or null if userInfo was null
     */
    public static String[] splitUserInfo(final String userInfo) {
        if (userInfo == null) {
            return null;
        }
        final int colon = userInfo.indexOf(':');
        if (colon >= 0) {
            return new String[] {
                userInfo.substring(0, colon),
                userInfo.substring(colon + 1)
            };
        }
        return new String[] {userInfo, ""};
    }

    /**
     * Resolve the effective Basic Auth credentials for a request.
     *
     * <p>Explicit {@code username}/{@code password} attributes win;
     * when neither is set the credentials embedded in the URL
     * ({@code http://user:pass@host/...}) are used as a fallback.</p>
     *
     * @param source the URL being fetched, may carry userInfo
     * @param username the configured username, may be null
     * @param password the configured password, may be null
     * @return {@code "user:password"} or null if no credentials available
     */
    public static String getBasicAuthCredentials(final URL source,
                                          final String username,
                                          final String password) {
        if (username != null || password != null) {
            return (username != null ? username : "")
                + ":" + (password != null ? password : "");
        }
        if (source != null) {
            final String[] parts = splitUserInfo(source.getUserInfo());
            if (parts != null) {
                return parts[0] + ":" + parts[1];
            }
        }
        return null;
    }

    /**
     * Encode credentials per RFC 7617 (ISO-8859-1 bytes, Base64).
     *
     * @param credentials {@code "user:password"}
     * @return Base64 encoded value, or null if credentials was null
     */
    public static String encodeBasicAuth(final String credentials) {
        if (credentials == null) {
            return null;
        }
        return new Base64Converter()
            .encode(credentials.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Parse an HTTP Digest challenge (the value of a
     * {@code WWW-Authenticate: Digest ...} header) into its parameters.
     *
     * <p>Parameter names are lower-cased; surrounding quotes are removed
     * from values.</p>
     *
     * @param header the header value, may be null
     * @return map of challenge parameters, or null if the value is not
     *         a Digest challenge
     */
    public static Map<String, String> parseDigestChallenge(final String header) {
        if (header == null) {
            return null;
        }
        String s = header.trim();
        if (s.length() <= 6 || !s.regionMatches(true, 0, "Digest", 0, 6)
            || !Character.isWhitespace(s.charAt(6))) {
            return null;
        }
        s = s.substring(6).trim();
        final Map<String, String> params = new LinkedHashMap<>();
        int i = 0;
        final int n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) == ','
                || Character.isWhitespace(s.charAt(i)))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            final int eq = s.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            final String key = s.substring(i, eq).trim()
                .toLowerCase(Locale.ENGLISH);
            i = eq + 1;
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            final String value;
            if (i < n && s.charAt(i) == '"') {
                i++;
                final StringBuilder sb = new StringBuilder();
                while (i < n) {
                    final char c = s.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        sb.append(s.charAt(i + 1));
                        i += 2;
                    } else if (c == '"') {
                        i++;
                        break;
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                value = sb.toString();
            } else {
                int j = i;
                while (j < n && s.charAt(j) != ',') {
                    j++;
                }
                value = s.substring(i, j).trim();
                i = j;
            }
            if (!key.isEmpty()) {
                params.put(key, value);
            }
        }
        return params;
    }

    /**
     * Collect the values of all {@code WWW-Authenticate} response headers.
     *
     * @param connection the (connected) connection to inspect
     * @return header values, never null but possibly empty
     */
    static List<String> getAuthenticateHeaders(final URLConnection connection) {
        final List<String> result = new ArrayList<>();
        final Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields != null) {
            for (final Map.Entry<String, List<String>> entry
                    : fields.entrySet()) {
                if (entry.getKey() != null
                    && entry.getKey().equalsIgnoreCase("WWW-Authenticate")
                    && entry.getValue() != null) {
                    result.addAll(entry.getValue());
                }
            }
        }
        if (result.isEmpty()) {
            final String single =
                connection.getHeaderField("WWW-Authenticate");
            if (single != null) {
                result.add(single);
            }
        }
        return result;
    }

    /**
     * Find the Digest challenge among {@code WWW-Authenticate} headers.
     *
     * @param challenges header values, may be null
     * @return the Digest challenge value, or null if there is none
     */
    public static String selectDigestChallenge(final List<String> challenges) {
        if (challenges == null) {
            return null;
        }
        for (final String challenge : challenges) {
            if (challenge != null) {
                final String trimmed = challenge.trim();
                if (trimmed.length() > 6
                    && trimmed.regionMatches(true, 0, "Digest", 0, 6)
                    && Character.isWhitespace(trimmed.charAt(6))) {
                    return challenge;
                }
            }
        }
        return null;
    }

    /**
     * Build the request path used as Digest {@code uri} parameter.
     *
     * @param url the URL being fetched
     * @return path plus query string, or {@code "/"} if there is no path
     */
    public static String getDigestUri(final URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        final String query = url.getQuery();
        return query != null ? path + "?" + query : path;
    }

    /**
     * Generate a random client nonce for Digest authentication.
     *
     * @return hex encoded random value
     */
    public static String generateCnonce() {
        final byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return toHex(bytes);
    }

    /**
     * Pick the quality-of-protection to answer with.
     *
     * @param qopOptions raw {@code qop} challenge value, may be null
     * @return {@code "auth"}, {@code "auth-int"} or null when the server
     *         sent no (usable) qop option
     */
    static String selectDigestQop(final String qopOptions) {
        if (qopOptions == null) {
            return null;
        }
        String options = qopOptions.trim();
        if (options.length() >= 2 && options.startsWith("\"")
            && options.endsWith("\"")) {
            options = options.substring(1, options.length() - 1);
        }
        boolean auth = false;
        boolean authInt = false;
        for (final String token : options.split("[,\\s]+")) {
            if (token.equalsIgnoreCase("auth")) {
                auth = true;
            } else if (token.equalsIgnoreCase("auth-int")) {
                authInt = true;
            }
        }
        if (auth) {
            return "auth";
        }
        if (authInt) {
            return "auth-int";
        }
        return null;
    }

    /**
     * Map a Digest {@code algorithm} value to a JCA message digest name.
     *
     * @param algorithm challenge algorithm, may be null (means MD5)
     * @return JCA algorithm name, or null if unsupported
     */
    static String toJcaDigestName(final String algorithm) {
        if (algorithm == null) {
            return "MD5";
        }
        final String normalized =
            algorithm.trim().toUpperCase(Locale.ENGLISH);
        if ("MD5".equals(normalized) || "MD5-SESS".equals(normalized)) {
            return "MD5";
        }
        if ("SHA-256".equals(normalized)
            || "SHA-256-SESS".equals(normalized)) {
            return "SHA-256";
        }
        if ("SHA-512-256".equals(normalized)
            || "SHA-512-256-SESS".equals(normalized)) {
            return "SHA-512/256";
        }
        return null;
    }

    /**
     * Hash data and return the lower-case hex representation.
     *
     * @param jcaAlgorithm JCA message digest name
     * @param data text to hash
     * @param charset charset used to get the bytes of the text
     * @return hex digest, or null if the algorithm is not available
     */
    static String hashHex(final String jcaAlgorithm, final String data,
                          final Charset charset) {
        try {
            final MessageDigest md = MessageDigest.getInstance(jcaAlgorithm);
            return toHex(md.digest(data.getBytes(charset)));
        } catch (final NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * Hex encode bytes (lower case).
     *
     * @param bytes bytes to encode
     * @return hex string
     */
    static String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            final int v = b & 0xFF;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /**
     * Build the value of an HTTP {@code Authorization} header answering
     * an HTTP Digest challenge (RFC 7616).
     *
     * <p>Supported algorithms are {@code MD5}, {@code SHA-256} and
     * {@code SHA-512-256} including their {@code -sess} variants.
     * Both {@code qop} modes ({@code auth} and {@code auth-int}, the
     * latter with an empty entity body as used by GET requests) as well
     * as the legacy RFC 2069 mode without {@code qop} are supported.</p>
     *
     * @param username username to authenticate with
     * @param password password to authenticate with
     * @param method HTTP method, e.g. {@code "GET"}
     * @param digestUri request path ({@code uri} parameter)
     * @param challenge parsed challenge as returned by
     *        {@link #parseDigestChallenge(String)}
     * @param nonceCount nonce count ({@code nc} parameter),
     *        e.g. {@code "00000001"}
     * @param cnonce client nonce, see {@link #generateCnonce()}
     * @return the header value starting with {@code "Digest "}, or null
     *         if the challenge cannot be answered
     */
    public static String buildDigestAuthorization(final String username,
            final String password, final String method,
            final String digestUri, final Map<String, String> challenge,
            final String nonceCount, final String cnonce) {
        if (username == null || password == null || method == null
            || digestUri == null || challenge == null) {
            return null;
        }
        final String realm = challenge.get("realm");
        final String nonce = challenge.get("nonce");
        if (realm == null || nonce == null) {
            return null;
        }
        final String algorithm = challenge.get("algorithm") != null
            ? challenge.get("algorithm") : "MD5";
        final String jcaAlgorithm = toJcaDigestName(algorithm);
        if (jcaAlgorithm == null) {
            return null;
        }
        final boolean sess = algorithm.trim().toUpperCase(Locale.ENGLISH)
            .endsWith("-SESS");
        Charset charset = StandardCharsets.ISO_8859_1;
        if ("utf-8".equalsIgnoreCase(challenge.get("charset"))) {
            charset = StandardCharsets.UTF_8;
        }
        final boolean userhash =
            "true".equalsIgnoreCase(challenge.get("userhash"));
        final String qop = selectDigestQop(challenge.get("qop"));
        final String nc = qop != null
            ? (nonceCount != null ? nonceCount : "00000001") : null;
        if (qop != null && cnonce == null) {
            return null;
        }
        String ha1 = hashHex(jcaAlgorithm,
            username + ":" + realm + ":" + password, charset);
        if (ha1 == null) {
            return null;
        }
        if (sess) {
            ha1 = hashHex(jcaAlgorithm, ha1 + ":" + nonce + ":" + cnonce,
                StandardCharsets.ISO_8859_1);
            if (ha1 == null) {
                return null;
            }
        }
        final String ha2;
        if ("auth-int".equals(qop)) {
            final String entityHash =
                hashHex(jcaAlgorithm, "", StandardCharsets.UTF_8);
            ha2 = hashHex(jcaAlgorithm,
                method + ":" + digestUri + ":" + entityHash,
                StandardCharsets.ISO_8859_1);
        } else {
            ha2 = hashHex(jcaAlgorithm, method + ":" + digestUri,
                StandardCharsets.ISO_8859_1);
        }
        if (ha2 == null) {
            return null;
        }
        final String response;
        if (qop != null) {
            response = hashHex(jcaAlgorithm,
                ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop
                    + ":" + ha2,
                StandardCharsets.ISO_8859_1);
        } else {
            response = hashHex(jcaAlgorithm, ha1 + ":" + nonce + ":" + ha2,
                StandardCharsets.ISO_8859_1);
        }
        if (response == null) {
            return null;
        }
        final String usernameParam;
        if (userhash) {
            usernameParam = hashHex(jcaAlgorithm, username + ":" + realm,
                charset);
            if (usernameParam == null) {
                return null;
            }
        } else {
            usernameParam = username;
        }
        final StringBuilder sb = new StringBuilder("Digest ");
        appendQuoted(sb, "username", usernameParam).append(", ");
        appendQuoted(sb, "realm", realm).append(", ");
        appendQuoted(sb, "nonce", nonce).append(", ");
        appendQuoted(sb, "uri", digestUri).append(", ");
        appendQuoted(sb, "response", response);
        sb.append(", algorithm=").append(algorithm);
        final String opaque = challenge.get("opaque");
        if (opaque != null) {
            sb.append(", ");
            appendQuoted(sb, "opaque", opaque);
        }
        if (qop != null) {
            sb.append(", qop=").append(qop);
            sb.append(", nc=").append(nc);
            sb.append(", ");
            appendQuoted(sb, "cnonce", cnonce);
        }
        if (userhash) {
            sb.append(", userhash=true");
        }
        if (charset.equals(StandardCharsets.UTF_8)) {
            sb.append(", charset=utf-8");
        }
        return sb.toString();
    }

    /**
     * Append a quoted Digest header parameter, escaping quotes.
     *
     * @param sb target
     * @param name parameter name
     * @param value parameter value
     * @return the target
     */
    private static StringBuilder appendQuoted(final StringBuilder sb,
            final String name, final String value) {
        sb.append(name).append("=\"");
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb;
    }

    /**
     * Interface implemented for reporting
     * progress of downloading.
     */
    public interface DownloadProgress {
        /**
         * begin a download
         */
        void beginDownload();

        /**
         * tick handler
         *
         */
        void onTick();

        /**
         * end a download
         */
        void endDownload();
    }

    /**
     * do nothing with progress info
     */
    public static class NullProgress implements DownloadProgress {

        /**
         * begin a download
         */
        @Override
        public void beginDownload() {
        }

        /**
         * tick handler
         *
         */
        @Override
        public void onTick() {
        }

        /**
         * end a download
         */
        @Override
        public void endDownload() {
        }
    }

    /**
     * verbose progress system prints to some output stream
     */
    public static class VerboseProgress implements DownloadProgress  {
        private int dots = 0;
        // CheckStyle:VisibilityModifier OFF - bc
        PrintStream out;
        // CheckStyle:VisibilityModifier ON

        /**
         * Construct a verbose progress reporter.
         * @param out the output stream.
         */
        public VerboseProgress(final PrintStream out) {
            this.out = out;
        }

        /**
         * begin a download
         */
        @Override
        public void beginDownload() {
            dots = 0;
        }

        /**
         * tick handler
         *
         */
        @Override
        public void onTick() {
            out.print(".");
            if (dots++ > DOTS_PER_LINE) {
                out.flush();
                dots = 0;
            }
        }

        /**
         * end a download
         */
        @Override
        public void endDownload() {
            out.println();
            out.flush();
        }
    }

    private class GetThread extends Thread {

        private final URL source;
        private final File dest;
        private final boolean hasTimestamp;
        private final long timestamp;
        private final DownloadProgress progress;
        private final int logLevel;

        private boolean success = false;
        private IOException ioexception = null;
        private BuildException exception = null;
        private InputStream is = null;
        private OutputStream os = null;
        private URLConnection connection;
        private int redirections = 0;
        private String userAgent = null;

        GetThread(final URL source, final File dest, final boolean h,
                  final long t, final DownloadProgress p, final int l, final String userAgent) {
            this.source = source;
            this.dest = dest;
            hasTimestamp = h;
            timestamp = t;
            progress = p;
            logLevel = l;
            this.userAgent = userAgent;
        }

        @Override
        public void run() {
            try {
                success = get();
            } catch (final IOException ioex) {
                ioexception = ioex;
            } catch (final BuildException bex) {
                exception = bex;
            }
        }

        private boolean get() throws IOException, BuildException {

            connection = openConnection(source, uname, pword);

            if (connection == null) {
                return false;
            }

            final boolean downloadSucceeded = downloadFile();

            //if (and only if) the use file time option is set, then
            //the saved file now has its timestamp set to that of the
            //downloaded file
            if (downloadSucceeded && useTimestamp)  {
                updateTimeStamp();
            }

            return downloadSucceeded;
        }


        private boolean redirectionAllowed(final URL aSource, final URL aDest) {
            if (!(aSource.getProtocol().equals(aDest.getProtocol()) || (HTTP
                    .equals(aSource.getProtocol()) && HTTPS.equals(aDest
                    .getProtocol())))) {
                final String message = "Redirection detected from "
                        + aSource.getProtocol() + " to " + aDest.getProtocol()
                        + ". Protocol switch unsafe, not allowed.";
                if (ignoreErrors) {
                    log(message, logLevel);
                    return false;
                } else {
                    throw new BuildException(message);
                }
            }

            redirections++;
            if (redirections > REDIRECT_LIMIT) {
                final String message = "More than " + REDIRECT_LIMIT
                        + " times redirected, giving up";
                if (ignoreErrors) {
                    log(message, logLevel);
                    return false;
                } else {
                    throw new BuildException(message);
                }
            }


            return true;
        }

        private URLConnection openConnection(final URL aSource, final String uname,
                                              final String pword) throws IOException {

            URL current = aSource;
            String currentUser = uname;
            String currentPassword = pword;
            String digestAuth = null;
            boolean digestAttempted = false;

            while (true) {
                final URLConnection connection = current.openConnection();
                configureConnection(connection, current, currentUser,
                    currentPassword, digestAuth);
                // connect to the remote site (may take some time)
                try {
                    connection.connect();
                } catch (final NullPointerException e) {
                    //bad URLs can trigger NPEs in some JVMs
                    throw new BuildException(
                        "Failed to parse " + source.toString(), e);
                }

                // non HTTP connections need no further handling
                if (!(connection instanceof HttpURLConnection)) {
                    return connection;
                }

                final HttpURLConnection httpConnection =
                    (HttpURLConnection) connection;
                final int responseCode = httpConnection.getResponseCode();

                // First check on a 301 / 302 (moved) response (HTTP only)
                if (isMoved(responseCode)) {
                    final String newLocation =
                        httpConnection.getHeaderField("Location");
                    final String message = current
                            + (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                                ? " permanently" : "")
                            + " moved to " + newLocation;
                    log(message, logLevel);
                    final URL newURL = new URL(current, newLocation);
                    if (!redirectionAllowed(current, newURL)) {
                        return null;
                    }
                    final String[] forwarded =
                        redirectCredentials(current, newURL, currentUser,
                            currentPassword);
                    current = newURL;
                    currentUser = forwarded[0];
                    currentPassword = forwarded[1];
                    digestAuth = null;
                    digestAttempted = false;
                    continue;
                }
                // next test for a 304 result (HTTP only)
                final long lastModified = httpConnection.getLastModified();
                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED
                        || (lastModified != 0 && hasTimestamp
                            && timestamp >= lastModified)) {
                    // not modified so no file download. just return
                    // instead and trace out something so the user
                    // doesn't think that the download happened when it
                    // didn't
                    log("Not modified - so not downloaded", logLevel);
                    return null;
                }
                // test for 401 result (HTTP only): answer a Digest
                // challenge when credentials are available
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED
                    && !digestAttempted) {
                    digestAttempted = true;
                    digestAuth = tryDigestAuth(current, currentUser,
                        currentPassword, connection);
                    if (digestAuth != null) {
                        httpConnection.disconnect();
                        continue;
                    }
                }
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    final List<String> challenges =
                        getAuthenticateHeaders(connection);
                    final StringBuilder message = new StringBuilder(
                        "HTTP Authorization failure for ").append(current);
                    if (!challenges.isEmpty()) {
                        for (final String challenge : challenges) {
                            log(challenge, logLevel);
                        }
                        message.append(" (");
                        message.append(String.join(", ", challenges));
                        message.append(")");
                    }
                    if (ignoreErrors) {
                        log(message.toString(), logLevel);
                        return null;
                    }
                    throw new BuildException(message.toString());
                }

                //REVISIT: at this point even non HTTP connections may
                //support the if-modified-since behaviour -we just check
                //the date of the content and skip the write if it is not
                //newer. Some protocols (FTP) don't include dates, of
                //course.
                return connection;
            }
        }

        /**
         * Set all request properties (authentication, user agent and
         * custom headers) on a fresh connection.
         *
         * @param connection the connection to configure
         * @param aSource the URL being fetched, may carry userInfo
         * @param uname configured username, may be null
         * @param pword configured password, may be null
         * @param digestAuth computed Digest authorization header value,
         *        or null to use preemptive Basic authentication
         */
        private void configureConnection(final URLConnection connection,
                final URL aSource, final String uname, final String pword,
                final String digestAuth) {
            // modify the headers
            // NB: things like user authentication could go in here too.
            if (hasTimestamp) {
                connection.setIfModifiedSince(timestamp);
            }
            // Set the user agent
            connection.addRequestProperty("User-Agent", this.userAgent);

            if (digestAuth != null) {
                connection.setRequestProperty("Authorization", digestAuth);
            } else {
                // prepare Basic Auth credentials. Explicit
                // username/password attributes win; otherwise fall back
                // to userInfo embedded in the URL
                // (http://user:pass@host/...).
                // We do not use the sun impl for portability, and always
                // use our own implementation for consistent testing.
                final String credentials =
                    getBasicAuthCredentials(aSource, uname, pword);
                if (credentials != null) {
                    connection.setRequestProperty("Authorization",
                        "Basic " + encodeBasicAuth(credentials));
                }
            }

            if (tryGzipEncoding) {
                connection.setRequestProperty("Accept-Encoding",
                    GZIP_CONTENT_ENCODING);
            }

            for (final Map.Entry<String, String> header : headers.entrySet()) {
                //we do not log the header value as it may contain sensitive data like passwords
                log(String.format("Adding header '%s' ", header.getKey()));
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).setInstanceFollowRedirects(false);
                connection.setUseCaches(httpUseCaches);
            }
        }

        /**
         * Compute the credentials forwarded when following a redirect.
         *
         * <p>Credentials are only forwarded when explicitly allowed via
         * {@code authenticateOnRedirect}. If the redirect target carries
         * its own userInfo, no credentials are forwarded so that the
         * target's userInfo takes precedence.</p>
         *
         * @param from the URL being redirected away from
         * @param to the redirect target
         * @param uname configured username, may be null
         * @param pword configured password, may be null
         * @return two element array with username and password to use
         *         for the target (elements may be null)
         */
        private String[] redirectCredentials(final URL from, final URL to,
                final String uname, final String pword) {
            if (!authenticateOnRedirect || to.getUserInfo() != null) {
                return new String[] {null, null};
            }
            final String[] effective = splitUserInfo(
                getBasicAuthCredentials(from, uname, pword));
            return effective != null ? effective
                : new String[] {null, null};
        }

        /**
         * Answer an HTTP Digest challenge for the failed request.
         *
         * @param aSource the URL being fetched
         * @param uname configured username, may be null
         * @param pword configured password, may be null
         * @param failedConnection the connection that got the 401
         * @return Digest authorization header value, or null when there
         *         is no Digest challenge, no credentials or the
         *         challenge cannot be answered
         */
        private String tryDigestAuth(final URL aSource, final String uname,
                final String pword, final URLConnection failedConnection) {
            final String digestChallenge =
                selectDigestChallenge(getAuthenticateHeaders(failedConnection));
            if (digestChallenge == null) {
                return null;
            }
            final String[] credentials = splitUserInfo(
                getBasicAuthCredentials(aSource, uname, pword));
            if (credentials == null) {
                return null;
            }
            final Map<String, String> challenge =
                parseDigestChallenge(digestChallenge);
            if (challenge == null || challenge.get("realm") == null
                || challenge.get("nonce") == null) {
                return null;
            }
            final String auth = buildDigestAuthorization(credentials[0],
                credentials[1], "GET", getDigestUri(aSource), challenge,
                "00000001", generateCnonce());
            if (auth != null) {
                log("Retrying with Digest authentication for " + aSource,
                    logLevel);
            }
            return auth;
        }

        private boolean downloadFile() throws IOException {
            for (int i = 0; i < numberRetries; i++) {
                // this three attempt trick is to get round quirks in different
                // Java implementations. Some of them take a few goes to bind
                // properly; we ignore the first couple of such failures.
                try {
                    is = connection.getInputStream();
                    break;
                } catch (final IOException ex) {
                    log("Error opening connection " + ex, logLevel);
                }
            }
            if (is == null) {
                log("Can't get " + source + " to " + dest, logLevel);
                if (ignoreErrors) {
                    return false;
                }
                throw new BuildException("Can't get " + source + " to " + dest,
                        getLocation());
            }

            if (tryGzipEncoding
                && GZIP_CONTENT_ENCODING.equals(connection.getContentEncoding())) {
                is = new GZIPInputStream(is);
            }

            os = Files.newOutputStream(dest.toPath());
            progress.beginDownload();
            boolean finished = false;
            try {
                final byte[] buffer = new byte[BIG_BUFFER_SIZE];
                int length;
                while (!isInterrupted() && (length = is.read(buffer)) >= 0) {
                    os.write(buffer, 0, length);
                    progress.onTick();
                }
                finished = !isInterrupted();
            } finally {
                FileUtils.close(os);
                FileUtils.close(is);

                // we have started to (over)write dest, but failed.
                // Try to delete the garbage we'd otherwise leave
                // behind.
                if (!finished) {
                    dest.delete();
                }
            }
            progress.endDownload();
            return true;
        }

        private void updateTimeStamp() {
            final long remoteTimestamp = connection.getLastModified();
            if (verbose)  {
                final Date t = new Date(remoteTimestamp);
                log("last modified = " + t.toString()
                    + ((remoteTimestamp == 0) ? " - using current time instead" : ""), logLevel);
            }
            if (remoteTimestamp != 0) {
                FILE_UTILS.setFileLastModified(dest, remoteTimestamp);
            }
        }

        /**
         * Has the download completed successfully?
         *
         * <p>Re-throws any exception caught during execution.</p>
         */
        boolean wasSuccessful() throws IOException, BuildException {
            if (ioexception != null) {
                throw ioexception;
            }
            if (exception != null) {
                throw exception;
            }
            return success;
        }

        /**
         * Closes streams, interrupts the download, may delete the
         * output file.
         */
        void closeStreams() {
            interrupt();
            FileUtils.close(os);
            FileUtils.close(is);
            if (!success && dest.exists()) {
                dest.delete();
            }
        }
    }
}
