package no.moldesoft.utils.debug.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.Provider.Type;
import javax.mail.event.ConnectionListener;
import javax.mail.event.TransportEvent;
import javax.mail.event.TransportListener;
import javax.mail.internet.InternetAddress;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * @author <a href="mailto:erling.molde@infored.no">Erling Molde</a>
 */
public class SaveForTransport extends Transport {

    private static final Logger LOG = LoggerFactory.getLogger(SaveForTransport.class);
    private static final String version = VersionUtil.get("$Revision: 2.5.2 $");
    private static final String JAVA_IO_TMPDIR = "java.io.tmpdir";
    private static final String MS_BASEDIR = "no.moldesoft.mailfolder";
    private static final String DEBUGMAIL_BASEDIR = "debugMail.mailfolder";
    private static final String MAIL_SAVED = "../mailSaved";
    private File baseDir;
    private int maxFilenameLength;
    private static final Map<ClassLoader, Config> clMap = new ConcurrentHashMap<>();
    private final Config config;
    private Transport originalTransport;

    private boolean inConnect;
    private final boolean realSend;

    public SaveForTransport(final Session session, final URLName name) {
        super(session, name);
        config = getConfig();
        if (config != null) {
            originalTransport = getOriginalTransport(session, name);
        }
        realSend = config != null && originalTransport != null;
        locateStorage();
        LOG.info("Meldinger lagres i " + this.baseDir);
        if (config == null) {
            LOG.info("Ingen konfigurasjon foreligger (meldinger blir aldri sendt, kun lagret).");
        } else {
            LOG.info("Konfigurasjon: " + config);
        }
    }

    private void locateStorage() {
        Supplier<String> tmpDirSupplier = () -> System.getProperty(JAVA_IO_TMPDIR);
        List<Pair<Boolean, Supplier<String>>> list = Arrays.asList(
                Pair.of(false, () -> this.session.getProperty(MS_BASEDIR)),
                Pair.of(false, () -> System.getProperty(DEBUGMAIL_BASEDIR)),
                Pair.of(false, () -> System.getProperty(MS_BASEDIR)),
                Pair.of(false, () -> {
                    String name1 = MS_BASEDIR + ".properties";
                    Properties p = new Properties();
                    try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(name1)) {
                        if (is != null) {
                            p.load(is);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (p.isEmpty()) {
                        try (InputStream is = getClass().getResourceAsStream(name1)) {
                            p.load(is);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    if (!p.isEmpty()) {
                        String baseDir = p.getProperty(MS_BASEDIR);
                        if (baseDir != null && !baseDir.isEmpty()) {
                            return baseDir;
                        }
                        baseDir = p.getProperty(DEBUGMAIL_BASEDIR);
                        if (baseDir != null && !baseDir.isEmpty()) {
                            return baseDir;
                        }
                    }
                    return null;
                }),
                Pair.of(true, tmpDirSupplier),
                Pair.of(false, tmpDirSupplier));
        for (Pair<Boolean, Supplier<String>> pair : list) {
            if (makeStorage(pair.b().get(), pair.a())) {
                break;
            }
        }
    }

    private boolean makeStorage(String baseDir, boolean attemptMakeSiblingDir) {
        if (baseDir != null && !baseDir.isEmpty()) {
            File bd = new File(baseDir);
            if (attemptMakeSiblingDir) {
                bd = new File(bd, MAIL_SAVED);
            }
            if (!bd.exists()) {
                bd.mkdirs();
            }
            if (bd.isDirectory() && bd.canWrite()) {
                this.baseDir = bd;
            }
            try {
                this.baseDir = new File(bd.getCanonicalPath());
            } catch (final IOException e) {
                this.baseDir = new File(bd.getAbsolutePath());
            }
            int pl;
            try {
                final String canonicalPath = this.baseDir.getCanonicalPath();
                pl = canonicalPath.length();
            } catch (final IOException e) {
                final String ap = this.baseDir.getAbsolutePath();
                pl = ap.length();
                int x = -1;
                int c = 0;
                while ((x = ap.indexOf('/', x + 1)) >= 0) {
                    c++;
                }
                while ((x = ap.indexOf('\\', x + 1)) >= 0) {
                    c++;
                }
                pl += c;
            }
            this.maxFilenameLength = 250 /* for Windows, *ix tåler lengre */ - pl - 1;
        }
        return this.baseDir != null;
    }

    private Transport getOriginalTransport(final Session session, final URLName name) {
        final Provider[] providers = session.getProviders();
        Provider op = null;
        for (final Provider provider : providers) {
            if (Type.TRANSPORT.equals(provider.getType())
                && "smtp".equals(provider.getProtocol())
                && !SaveForTransport.class.getName().equals(provider.getClassName())) {
                op = provider;
                break;
            }
        }
        Transport origTransportInstance = null;
        if (op != null) {
            try {
                @SuppressWarnings("unchecked") final Class<Transport> providerClass = (Class<Transport>) Class.forName(
                        op.getClassName());
                final Constructor<Transport> constructor = providerClass.getConstructor(Session.class, URLName.class);
                origTransportInstance = constructor.newInstance(session, name);
            } catch (final ClassNotFoundException | SecurityException | NoSuchMethodException | IllegalArgumentException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
                LOG.warn(e.toString(), e);
            }
        }
        return origTransportInstance;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Transport#sendMessage(javax.mail.Message,
     * javax.mail.Address[])
     */
    @Override
    public void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
        final long currentTimeMillis = System.currentTimeMillis();
        final String dateSent = String.format("%tF_%<tH%<tM%<tS_%<tL", new Date(currentTimeMillis));
        final String suffix = getSuffix(dateSent);
        final String subject = message.getSubject();
        final File destFile = new File(baseDir, dateSent
                                                + '_'
                                                + suffix
                                                + subjectForName(subject, dateSent.length() + 1 + suffix.length()));
        FileOutputStream fos;
        LOG.info("Melding: " + subject);
        LOG.info("Sendt til: " + Arrays.toString(addresses));
        LOG.info("Melding lagres som: " + destFile);

        Address[] realAddresses = null;
        if (realSend) {
            realAddresses = config.filter(addresses);
        }

        try {
            fos = new FileOutputStream(destFile);
            final PrintWriter w = new PrintWriter(fos, false);

            if (realAddresses == null || realAddresses.length == 0) {
                w.println("Meldingen er IKKE SENDT til noen.");
            } else {
                w.println("Meldingen er sendt til:");
                for (final Address address : realAddresses) {
                    w.print("\t");
                    final String strAdr = address.toString();
                    w.println(strAdr);
                }
            }
            w.println();
            w.flush();

            message.writeTo(fos);
            fos.close();
            notifyTransportListeners(TransportEvent.MESSAGE_DELIVERED, addresses, null, null, message);
        } catch (final FileNotFoundException e) {
            LOG.warn("Filename: " + destFile, e);
            notifyTransportListeners(TransportEvent.MESSAGE_NOT_DELIVERED, addresses, null, null, message);
        } catch (final IOException e) {
            LOG.warn("Filename: " + destFile, e);
            notifyTransportListeners(TransportEvent.MESSAGE_PARTIALLY_DELIVERED, addresses, null, null, message);
        }

        if (realSend) {
            config.amendSubject(message);
            originalTransport.sendMessage(message, realAddresses);
        }
    }

    private String subjectForName(final String subject, final int maxLen) {
        if (subject == null || subject.length() == 0) {
            return "";
        }
        int a = 0, b = 0;
        final int ml = maxFilenameLength - maxLen;
        final int sl = subject.length();
        final int l = sl >= ml ? ml : sl + 1;
        final char[] cb = new char[l];
        while (a < sl) {
            char c = subject.charAt(a++);
            if (c < 0x100) {
                switch (c) {
                    case ' ':
                        c = '-';
                        break;
                    case 'Æ':
                    case 'Å':
                        c = 'A';
                        break;
                    case 'Ø':
                        c = 'O';
                        break;
                    case 'æ':
                    case 'å':
                        c = 'a';
                        break;
                    case 'ø':
                        c = 'o';
                        break;
                }
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0'
                                                                    && c <= '9' || c == '-' || c == '_') {
                    if (b == 0 && c != '_') {
                        cb[b++] = '_';
                    }
                    cb[b++] = c;
                    if (b >= l) {
                        break;
                    }
                }
            }
        }
        return new String(cb, 0, b);
    }

    private static String lastDateSent;
    private static int seq;

    private static synchronized String getSuffix(final String dateSent) {
        if (!dateSent.equals(lastDateSent)) {
            lastDateSent = dateSent;
            seq = 0;
        }
        return String.format("%03d", seq++);
    }

    private Config getConfig() {
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Config config = clMap.computeIfAbsent(cl, key -> Optional.ofNullable(
                        Config.createConfig(cl, "debugMail.properties"))
                .orElse(Config.NULL));
        return config == Config.NULL ? null : config;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * javax.mail.Transport#addTransportListener(javax.mail.event.TransportListener
     * )
     */
    @Override
    public synchronized void addTransportListener(final TransportListener listener) {
        if (realSend) {
            originalTransport.addTransportListener(listener);
        } else {
            super.addTransportListener(listener);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Transport#removeTransportListener(javax.mail.event.
     * TransportListener)
     */
    @Override
    public synchronized void removeTransportListener(final TransportListener listener) {
        if (realSend) {
            originalTransport.removeTransportListener(listener);
        } else {
            super.removeTransportListener(listener);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * javax.mail.Service#addConnectionListener(javax.mail.event.ConnectionListener
     * )
     */
    @Override
    public synchronized void addConnectionListener(final ConnectionListener listener) {
        if (realSend) {
            originalTransport.addConnectionListener(listener);
        } else {
            super.addConnectionListener(listener);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Service#close()
     */
    @Override
    public synchronized void close() throws MessagingException {
        if (realSend) {
            originalTransport.close();
        } else {
            super.close();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Service#connect()
     */
    @Override
    public void connect() throws MessagingException {
        if (realSend) {
            originalTransport.connect();
        } else {
            super.connect();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Service#connect(java.lang.String, int, java.lang.String,
     * java.lang.String)
     */
    @Override
    public void connect(final String arg0, final int arg1, final String arg2,
                        final String arg3) throws MessagingException {
        if (realSend) {
            originalTransport.connect(arg0, arg1, arg2, arg3);
        } else {
            super.connect(arg0, arg1, arg2, arg3);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Service#connect(java.lang.String, java.lang.String,
     * java.lang.String)
     */
    @Override
    public void connect(final String arg0, final String arg1, final String arg2) throws MessagingException {
        if (realSend) {
            originalTransport.connect(arg0, arg1, arg2);
        } else {
            super.connect(arg0, arg1, arg2);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Service#getURLName()
     */
    @Override
    public URLName getURLName() {
        if (realSend) {
            return originalTransport.getURLName();
        }
        return super.getURLName();
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Service#isConnected()
     */
    @Override
    public boolean isConnected() {
        if (realSend) {
            return originalTransport.isConnected();
        }
        return super.isConnected();
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Service#removeConnectionListener(javax.mail.event.
     * ConnectionListener)
     */
    @Override
    public synchronized void removeConnectionListener(final ConnectionListener listener) {
        if (realSend) {
            originalTransport.removeConnectionListener(listener);
        } else {
            super.removeConnectionListener(listener);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Service#toString()
     */
    @Override
    public String toString() {
        return realSend ? super.toString() : originalTransport.toString();
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.mail.Service#protocolConnect(java.lang.String, int,
     * java.lang.String, java.lang.String)
     */
    @Override
    protected boolean protocolConnect(final String host, final int port,
                                      final String user, final String password) {
        return baseDir != null;
    }

    static class Pair<A, B> {
        private final A a;
        private final B b;

        Pair(A a, B b) {
            this.a = a;
            this.b = b;
        }

        public A a() {
            return a;
        }

        public B b() {
            return b;
        }

        static <A, B> Pair<A, B> of(A a, B b) {
            return new Pair<>(a, b);
        }
    }

    static class Config {

        private final List<Pattern> rules = new ArrayList<>();
        private final List<InternetAddress> replacements = new ArrayList<>();
        private String subjectPrefix;
        static final Config NULL = new Config();

        Address[] filter(final Address[] addresses) {
            final List<Address> list = new ArrayList<>();
            if (rules.isEmpty()) {
                list.addAll(replacements);
            } else {
                final Set<String> set = new HashSet<>();
                for (final Address a : addresses) {
                    if (a instanceof InternetAddress) {
                        final InternetAddress ia = (InternetAddress) a;
                        final String address = ia.getAddress().toLowerCase();
                        if (!set.contains(address)) {
                            for (final Pattern allow : rules) {
                                if (allow.matcher(address).matches()) {
                                    set.add(address);
                                    list.add(a);
                                    break;
                                }
                            }
                        }
                    }
                }
                for (final InternetAddress a : replacements) {
                    final String e = a.getAddress().toLowerCase();
                    if (set.add(e)) {
                        list.add(a);
                    }
                }
            }
            return list.toArray(new Address[0]);
        }

        void amendSubject(Message message) {
            if (subjectPrefix != null) {
                try {
                    String origSubject = message.getSubject();
                    String subject = subjectPrefix + ' ' + origSubject;
                    message.setSubject(subject);
                } catch (MessagingException e) {
                    log.warn("Amend subject failed: " + e.getMessage(), e);
                }
            }
        }

        private Config() {
        }

        private void addRule(final Pattern rule) {
            rules.add(rule);
        }

        private void addReplacement(final InternetAddress addr) {
            replacements.add(addr);
        }

        private static final Logger log = LoggerFactory.getLogger(Config.class);
        private static final Pattern BAR = Pattern.compile("\\s*\\|\\s*");
        private static final Pattern COMMASPACE = Pattern.compile("\\s*,\\s*");

        public static Config createConfig(final ClassLoader classLoader, final String propertyfile) {
            Config config = null;
            final URL resource = classLoader.getResource(propertyfile);
            Properties p = new Properties();
            if (resource != null) {
                try (InputStream openStream = resource.openStream()) {
                    p.load(openStream);
                    p.stringPropertyNames().forEach(name -> {
                        String property = p.getProperty(name);
                        String trimmed = property.trim();
                        if (trimmed.isEmpty()) {
                            p.remove(name);
                        } else {
                            p.setProperty(name, trimmed);
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            config = processAllow(config, getAllows(p));
            try {
                config = processReplace(config, getReplace(p));
            } catch (UnsupportedEncodingException e) {
                throw new UncheckedIOException(e);
            }
            if (config != null) {
                String subjectPrefix = getSubjectPrefix(p);
                if (subjectPrefix != null && subjectPrefix.length() > 0) {
                    config.setSubjectPrefix(subjectPrefix);
                }
            }
            return config;
        }

        private void setSubjectPrefix(String subjectPrefix) {
            this.subjectPrefix = subjectPrefix;
        }

        private static String getAllows(final Properties p) {
            String allows = p == null ? null : p.getProperty("allow");
            if (allows == null) {
                allows = System.getProperty("debugMail.allow");
            }
            return allows;
        }

        private static String getReplace(final Properties p) {
            String replace = p == null ? null : p.getProperty("replace");
            if (replace == null) {
                replace = System.getProperty("debugMail.replace");
            }
            return replace;
        }

        private static String getSubjectPrefix(Properties p) {
            String pfx = p == null ? null : p.getProperty("subjectPrefix");
            if (pfx == null) {
                pfx = System.getProperty("debugMail.subjectPrefix");
            }
            if (pfx != null) {
                pfx = pfx.trim();
            }
            return pfx;
        }

        private static Config processAllow(Config config, String allows) {
            if (allows != null) {
                final String[] allowRules = COMMASPACE.split(allows);
                for (final String allowRule : allowRules) {
                    if (allowRule.length() > 0) {
                        Pattern rule;
                        try {
                            rule = Pattern.compile(allowRule);
                            if (config == null) {
                                config = new Config();
                            }
                            config.addRule(rule);
                        } catch (final Exception e) {
                            log.warn("allowRule: " + allowRule, e);
                        }
                    }
                }
            }
            return config;
        }

        private static Config processReplace(Config config, String replace)
                throws UnsupportedEncodingException {
            if (replace != null) {
                final String[] replacements = COMMASPACE.split(replace);
                for (final String replacement : replacements) {
                    final String[] parts = BAR.split(replacement);
                    final InternetAddress address = new InternetAddress();
                    String addr;
                    if (parts.length > 1) {
                        addr = parts[1].trim();
                        address.setPersonal(parts[0].trim());
                    } else {
                        addr = parts[0].trim();
                    }
                    if (addr.length() == 0) {
                        continue;
                    }
                    address.setAddress(addr);
                    if (config == null) {
                        config = new Config();
                    }
                    config.addReplacement(address);
                }
            }
            return config;
        }

        @Override
        public String toString() {
            final StringBuilder b = new StringBuilder();
            final List<Pattern> rulesList = this.rules;
            final List<InternetAddress> replacementsList = this.replacements;
            if (rulesList.size() > 0) {
                b.append("allow=[");
                final int mark = b.length();
                for (final Pattern pattern : rulesList) {
                    if (b.length() > mark) {
                        b.append(", ");
                    }
                    b.append(pattern.toString());
                }
                b.append(']');
            }
            if (replacementsList.size() > 0) {
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append("replace=[");
                final int mark = b.length();
                for (final InternetAddress internetAddress : replacementsList) {
                    if (b.length() > mark) {
                        b.append(", ");
                    }
                    b.append(internetAddress.toString());
                }
                b.append(']');
            }
            return b.toString();
        }
    }

    static {
        LOG.info("Initializing " + SaveForTransport.class.getName()
                 + " version " + version);
    }
}