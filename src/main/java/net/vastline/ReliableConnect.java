/**
 * Copyright (c) 2015, Vastline and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 */

package net.vastline;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReliableConnect {

    private static final Logger LOG = LoggerFactory.getLogger(ReliableConnect.class);

    /**
     * user@host[:port] pathToSshPrivateKey
     * group1 - user
     * group2 - host
     * group4 - port
     * group5 - pathToSshPrivateKey
     */
    private static final Pattern SESSION_TARGET_PATTERN =
            Pattern.compile("^\\s*([A-Za-z0-9\\.\\-_]+)\\s*@\\s*([A-Za-z0-9\\.\\-_]+)\\s*(:\\s*([0-9]+))?\\s+(.+)");

    /**
     * L bindPort:targetHost:targetPort
     * group1: bindPort
     * group2: targetHost
     * group3: targetPort
     */
    private static final Pattern SESSION_LOCAL_PORT_PATTERN =
            Pattern.compile("^L\\s*([0-9]+)\\s*:\\s*([A-Za-z0-9\\.\\-_]+)\\s*:\\s*([0-9]+)$");

    /**
     * R bindPort:targetHost:targetPort
     * group1: bindPort
     * group2: targetHost
     * group3: targetPort
     */
    private static final Pattern SESSION_REMOTE_PORT_PATTERN =
            Pattern.compile("^R\\s*([0-9]+)\\s*:\\s*([A-Za-z0-9\\.\\-_]+)\\s*:\\s*([0-9]+)$");

    @SuppressWarnings({"InfiniteLoopStatement"})
    public static void main(String[] args) throws Exception {
        LOG.info("\nStarted\n");

        String configPath = args.length > 0 ? args[0] : "rc.conf";

        List<SessionInfo> sessionInfoList = new ArrayList<SessionInfo>();

        BufferedReader reader = new BufferedReader(new FileReader(configPath));
        String line;
        SessionInfo sessionInfo = null;
        LOG.debug("Read configuration from: {}", configPath);
        while ((line = reader.readLine()) != null) {
            LOG.trace("config line: {}", line);

            int indexOf = line.indexOf("#");
            if (indexOf >= 0) {
                line = line.substring(0, indexOf);
            }

            line = line.trim();

            Matcher matcher = SESSION_TARGET_PATTERN.matcher(line);
            if (matcher.matches()) {
                String user = matcher.group(1);
                String host = matcher.group(2);
                int port = Integer.parseInt(matcher.group(4) != null ? matcher.group(4) : "22");
                String pathToSshPrivateKey = matcher.group(5);
                sessionInfo = new SessionInfo(host, port, user, pathToSshPrivateKey);
                sessionInfoList.add(sessionInfo);
                LOG.trace("accepted");
                continue;
            }

            if (sessionInfo == null) {
                LOG.trace("skipped");
                continue;
            }

            matcher = SESSION_LOCAL_PORT_PATTERN.matcher(line);
            if (matcher.matches()) {
                int bindPort = Integer.parseInt(matcher.group(1));
                String targetHost = matcher.group(2);
                int targetPort = Integer.parseInt(matcher.group(3));
                sessionInfo.addLocalPort(new SessionInfo.PortInfo(bindPort, targetHost, targetPort));
                LOG.trace("accepted");
                continue;
            }

            matcher = SESSION_REMOTE_PORT_PATTERN.matcher(line);
            if (matcher.matches()) {
                int bindPort = Integer.parseInt(matcher.group(1));
                String targetHost = matcher.group(2);
                int targetPort = Integer.parseInt(matcher.group(3));
                sessionInfo.addRemotePort(new SessionInfo.PortInfo(bindPort, targetHost, targetPort));
                LOG.trace("accepted");
                continue;
            }
            LOG.trace("skipped");
        }

        List<Thread> threadList = new ArrayList<Thread>();
        for (final SessionInfo info : sessionInfoList) {
            Thread thread = new Thread() {
                @Override
                public void run() {

                    Session session = null;

                    while (true) {
                        LOG.debug("{} Checking connection...", info.getHost());
                        try {
                            if (session == null || !session.isConnected()) {
                                LOG.debug("{} No connection.", info.getHost());
                                if (session != null) {
                                    LOG.debug("{info.getHost()} Force session disconnect...", info.getHost());
                                    session.forceDisconnect();
                                }
                                session = connect(info);
                            } else {
                                session.sendKeepAliveMsg();
                            }

                            TimeUnit.MINUTES.sleep(5);

                        } catch (Exception e) {
                            LOG.error("{} ERROR: {}", info.getHost(), e.toString() + (e.getCause() != null ? " CAUSED BY: " + e.getCause().toString() : ""));
                            LOG.info("{} Trying to reconnect in 3 mins...", info.getHost());
                            try {

                                TimeUnit.MINUTES.sleep(3);

                            } catch (InterruptedException e1) {
                                throw new RuntimeException(e1);
                            }
                        }
                    }
                }
            };
            thread.start();
            threadList.add(thread);
        }

        for (Thread thread : threadList) {
            thread.join();
        }
    }

    private static Session getSession(SessionInfo sessionInfo) throws JSchException {
        JSch jsch = new JSch();
        jsch.addIdentity(sessionInfo.getPrivateKeyPath());
        Session session = jsch.getSession(sessionInfo.getUser(), sessionInfo.getHost(), sessionInfo.getPort());

        session.setConfig("StrictHostKeyChecking", "no");
        return session;
    }

    private static Session connect(SessionInfo sessionInfo) throws JSchException {
        Session session = getSession(sessionInfo);

        LOG.info("{} Connecting...", sessionInfo.getHost());

        session.connect();

        LOG.info("{} Connected.", sessionInfo.getHost());

        for (SessionInfo.PortInfo portInfo : sessionInfo.getLocalPorts()) {

            LOG.info("{} L: {}", sessionInfo.getHost(), portInfo);

            session.setPortForwardingL(
                    portInfo.getBindAddress(), portInfo.getBindPort(),
                    portInfo.getTargetHost(), portInfo.getTargetPort());
        }

        for (SessionInfo.PortInfo portInfo : sessionInfo.getRemotePorts()) {

            LOG.info("{} R: {}", sessionInfo.getHost(), portInfo);

            session.setPortForwardingR(
                    portInfo.getBindAddress(), portInfo.getBindPort(),
                    portInfo.getTargetHost(), portInfo.getTargetPort());
        }

        return session;
    }

    public static class SessionInfo {

        private String host;
        private int port;
        private String user;
        private String privateKeyPath;

        private List<PortInfo> localPorts = new ArrayList<PortInfo>();
        private List<PortInfo> remotePorts = new ArrayList<PortInfo>();

        public SessionInfo(String host, int port, String user, String privateKeyPath) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.privateKeyPath = privateKeyPath;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getUser() {
            return user;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public List<PortInfo> getLocalPorts() {
            return localPorts;
        }

        public void addLocalPort(PortInfo portInfo) {
            localPorts.add(portInfo);
        }

        public List<PortInfo> getRemotePorts() {
            return remotePorts;
        }

        public void addRemotePort(PortInfo portInfo) {
            remotePorts.add(portInfo);
        }

        public static class PortInfo {

            private String bindAddress;
            private int bindPort;
            private String targetHost;
            private int targetPort;

            public PortInfo(int bindPort, String targetHost, int targetPort) {
                this("*", bindPort, targetHost, targetPort);
            }

            public PortInfo(String bindAddress, int bindPort, String targetHost, int targetPort) {
                this.bindAddress = bindAddress;
                this.bindPort = bindPort;
                this.targetHost = targetHost;
                this.targetPort = targetPort;
            }

            public String getBindAddress() {
                return bindAddress;
            }

            public int getBindPort() {
                return bindPort;
            }

            public String getTargetHost() {
                return targetHost;
            }

            public int getTargetPort() {
                return targetPort;
            }

            @Override
            public String toString() {
                return "Port [ " + bindAddress + " : " + bindPort + " : " + targetHost + " : " + targetPort + " ]";
            }
        }
    }
}
