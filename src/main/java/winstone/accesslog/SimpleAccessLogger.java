/*
 * Copyright 2003-2006 Rick Knowles <winstone-devel at lists sourceforge net>
 * Distributed under the terms of either:
 * - the common development and distribution license (CDDL), v1.0; or
 * - the GNU Lesser General Public License, v2.1 or later
 */
package winstone.accesslog;

import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import winstone.Logger;
import winstone.WinstoneResourceBundle;
import winstone.cmdline.Option;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.StandardOpenOption;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * Simulates an apache "combined" style logger, which logs User-Agent, Referer, etc
 *
 * @author <a href="mailto:rick_knowles@hotmail.com">Rick Knowles</a>
 * @version $Id: SimpleAccessLogger.java,v 1.5 2006/03/24 17:24:19 rickknowles Exp $
 */
public class SimpleAccessLogger extends AbstractLifeCycle implements RequestLog {

    public static final WinstoneResourceBundle ACCESSLOG_RESOURCES =
            new WinstoneResourceBundle("winstone.accesslog.LocalStrings");

    private static final DateFormat DF = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z");
    private static final String COMMON = "###ip### - ###user### ###time### \"###uriLine###\" ###status### ###size###";
    private static final String COMBINED = COMMON + " \"###referer###\" \"###userAgent###\"";
    private static final String RPROXYCOMBINED = "###x-forwarded-for### " + COMBINED;
    private static final String RESIN = COMMON + " \"###userAgent###\"";

    private OutputStream outStream;
    private PrintWriter outWriter;
    private String pattern;
    private String fileName;

    public SimpleAccessLogger(String webAppName, Map<String, String> startupArgs)
            throws IOException {

        // Get pattern
        String patternType = Option.SIMPLE_ACCESS_LOGGER_FORMAT.get(startupArgs);
        if (patternType.equalsIgnoreCase("combined")) {
            this.pattern = COMBINED;
        } else if (patternType.equalsIgnoreCase("common")) {
            this.pattern = COMMON;
        } else if (patternType.equalsIgnoreCase("resin")) {
            this.pattern = RESIN;
        } else if (patternType.equalsIgnoreCase("rproxycombined")) {
            this.pattern = RPROXYCOMBINED;
        } else {
            this.pattern = patternType;
        }

        // Get filename
        String filePattern =  Option.SIMPLE_ACCESS_LOGGER_FILE.get(startupArgs);
        this.fileName = WinstoneResourceBundle.globalReplace(filePattern,
                new String [][] {
                    {"###webapp###", webAppName}});

        File file = new File(this.fileName);
        File parentFile = file.getParentFile();
        try {
            Files.createDirectories(parentFile.toPath());
        } catch (Exception ex) {
            Logger.logDirectMessage(Logger.WARNING, null, "Failed to mkdirs " + parentFile.getAbsolutePath(), ex);
        }
        try {
            this.outStream = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (InvalidPathException e) {
            throw new IOException(e);
        }
        this.outWriter = new PrintWriter(new OutputStreamWriter(this.outStream, StandardCharsets.UTF_8), true);

        Logger.log(Logger.DEBUG, ACCESSLOG_RESOURCES, "SimpleAccessLogger.Init",
                this.fileName, patternType);
    }

    @Override
    public void log(Request request, Response response) {
        String uriLine = request.getMethod() + " " + request.getRequestURI() + " " + request.getProtocol();
        int status = response.getStatus();
        long size = response.getContentCount();
        String date;
        synchronized (DF) {
            date = DF.format(new Date());
        }

        // mimic https://github.com/eclipse/jetty.project/blob/jetty-9.4.0.v20161208/jetty-server/src/main/java/org/eclipse/jetty/server/AbstractNCSARequestLog.java#L130
        Authentication authentication = request.getAuthentication();
        String remoteUser;
        if (authentication instanceof Authentication.User) {
            Authentication.User user = (Authentication.User) authentication;
            remoteUser = user.getUserIdentity().getUserPrincipal().getName();
        } else {
            remoteUser = null;
        }

        String logLine = WinstoneResourceBundle.globalReplace(this.pattern, new String[][] {
                {"###x-forwarded-for###", nvl(request.getHeader("X-Forwarded-For"))},
                {"###x-forwarded-host###", nvl(request.getHeader("X-Forwarded-Host"))},
                {"###x-forwarded-proto###", nvl(request.getHeader("X-Forwarded-Proto"))},
                {"###x-forwarded-protocol###", nvl(request.getHeader("X-Forwarded-Protocol"))},
                {"###x-forwarded-server###", nvl(request.getHeader("X-Forwarded-Server"))},
                {"###x-forwarded-ssl###", nvl(request.getHeader("X-Forwarded-Ssl"))},
                {"###x-requested-with###", nvl(request.getHeader("X-Requested-With"))},
                {"###x-do-not-track###", nvl(request.getHeader("X-Do-Not-Track"))},
                {"###dnt###", nvl(request.getHeader("DNT"))},
                {"###via###", nvl(request.getHeader("Via"))},
                {"###ip###", request.getRemoteHost()},
                {"###user###", nvl(remoteUser)},
                {"###time###", "[" + date + "]"},
                {"###uriLine###", uriLine},
                {"###status###", "" + status},
                {"###size###", "" + size},
                {"###referer###", nvl(request.getHeader("Referer"))},
                {"###userAgent###", nvl(request.getHeader("User-Agent"))}
        });
        this.outWriter.println(logLine);
    }

    private static String nvl(String input) {
        return input == null ? "-" : input;
    }

    @Override
    protected void doStop() throws Exception {
        Logger.log(Logger.DEBUG, ACCESSLOG_RESOURCES, "SimpleAccessLogger.Close", this.fileName);
        if (this.outWriter != null) {
            this.outWriter.flush();
            this.outWriter.close();
            this.outWriter = null;
        }
        if (this.outStream != null) {
            try {
                this.outStream.close();
            } catch (IOException err) {}
            this.outStream = null;
        }
        this.fileName = null;
//        this.webAppConfig = null;
    }
}
