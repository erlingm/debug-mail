package biz.infored.utils.debug.mail;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Provider;
import javax.mail.Provider.Type;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.URLName;
import javax.mail.event.ConnectionListener;
import javax.mail.event.TransportEvent;
import javax.mail.event.TransportListener;
import javax.mail.internet.InternetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="mailto:erling.molde@infored.no">Erling Molde</a>
 */
public class SaveForTransport extends Transport {

	private static final Logger LOG = LoggerFactory
			.getLogger(SaveForTransport.class);
	private static final String version = VersionUtil.get("$Revision: 1.12 $");
	private static final String JAVA_IO_TMPDIR = "java.io.tmpdir";
	private static final String INFORED_BASEDIR = "infored.mailfolder";
	private static final String MAIL_SAVED = "../mailSaved";
	private File baseDir;
	private int maxFilenameLength;
	private static Map<ClassLoader, Config> clMap = new HashMap<ClassLoader, Config>();
	private Config config;
	private Transport originalTransport;

	private boolean inConnect;
	private final boolean realSend;

	/**
	 * @param session
	 * @param name
	 */
	public SaveForTransport(final Session session, final URLName name) {
		super(session, name);
		try {
			config = getConfig();
		} catch (final IOException e1) {
			throw new RuntimeException(e1);
		}
		if (config != null)
			originalTransport = getOriginalTransport(session, name);
		realSend = config != null && originalTransport != null;
		STAGES: for (int stage = 0; this.baseDir == null; stage++) {
			String baseDir = null;
			switch (stage) {
			case 0:
				baseDir = this.session.getProperty(INFORED_BASEDIR);
				break;
			case 1:
				baseDir = System.getProperty(INFORED_BASEDIR);
				break;
			case 2:
				final String name1 = INFORED_BASEDIR + ".properties";
				InputStream is = Thread.currentThread().getContextClassLoader()
						.getResourceAsStream(name1);
				if (is == null)
					is = getClass().getResourceAsStream(name1);
				if (is != null) {
					final Properties p = new Properties();
					try {
						p.load(is);
					} catch (final IOException e) {
					}
					try {
						is.close();
					} catch (final IOException e) {
					}
					baseDir = p.getProperty(INFORED_BASEDIR);
				}
				break;
			case 3:
			case 4:
				baseDir = System.getProperty(JAVA_IO_TMPDIR);
				break;
			default:
				break STAGES;
			}
			if (baseDir != null) {
				File bd = new File(baseDir);
				if (stage == 3)
					bd = new File(bd, MAIL_SAVED);
				if (!bd.exists())
					bd.mkdirs();
				if (bd.isDirectory() && bd.canWrite())
					this.baseDir = bd;
				try {
					this.baseDir = new File(bd.getCanonicalPath());
				} catch (final IOException e) {
					this.baseDir = new File(bd.getAbsolutePath());
				}
				int pl;
				try {
					final String canonicalPath = this.baseDir
							.getCanonicalPath();
					pl = canonicalPath.length();
				} catch (final IOException e) {
					final String ap = this.baseDir.getAbsolutePath();
					pl = ap.length();
					int x = -1;
					int c = 0;
					while ((x = ap.indexOf('/', x + 1)) >= 0)
						c++;
					x = -1;
					while ((x = ap.indexOf('\\', x + 1)) >= 0)
						c++;
					pl += c;
				}
				this.maxFilenameLength = 250 /* for Windows, *ix tåler lengre */- pl - 1;
			}
		}
		LOG.info("Meldinger lagres i " + this.baseDir);
		if (config == null)
			LOG.info("Ingen konfigurasjon foreligger (meldinger blir aldri sendt, kun lagret).");
		else
			LOG.info("Konfigurasjon: " + config);
	}

	private Transport getOriginalTransport(final Session session,
			final URLName name) {
		final Provider[] providers = session.getProviders();
		Provider op = null;
		for (final Provider provider : providers)
			if (Type.TRANSPORT.equals(provider.getType())
					&& "smtp".equals(provider.getProtocol())
					&& !SaveForTransport.class.getName().equals(
							provider.getClassName())) {
				op = provider;
				break;
			}
		Transport origTransportInstance = null;
		if (op != null)
			try {
				@SuppressWarnings("unchecked")
				final Class<Transport> providerClass = (Class<Transport>) Class
						.forName(op.getClassName());
				final Constructor<Transport> constructor = providerClass
						.getConstructor(Session.class, URLName.class);
				origTransportInstance = constructor.newInstance(session, name);
			} catch (final ClassNotFoundException e) {
				LOG.warn(e.toString(), e);
			} catch (final SecurityException e) {
				LOG.warn(e.toString(), e);
			} catch (final NoSuchMethodException e) {
				LOG.warn(e.toString(), e);
			} catch (final IllegalArgumentException e) {
				LOG.warn(e.toString(), e);
			} catch (final InstantiationException e) {
				LOG.warn(e.toString(), e);
			} catch (final IllegalAccessException e) {
				LOG.warn(e.toString(), e);
			} catch (final InvocationTargetException e) {
				LOG.warn(e.toString(), e);
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
	public void sendMessage(final Message message, final Address[] addresses)
			throws MessagingException {
		final long currentTimeMillis = System.currentTimeMillis();
		final String dateSent = String.format("%1$tF_%1$tH%1$tM%1$tS_%1$tL",
				new Date(currentTimeMillis));
		final String suffix = getSuffix(dateSent);
		final String subject = message.getSubject();
		final File destFile = new File(baseDir, dateSent
				+ '_'
				+ suffix
				+ subjectForName(subject,
						dateSent.length() + 1 + suffix.length()));
		FileOutputStream fos;
		LOG.info("Melding: " + subject);
		LOG.info("Sendt til: " + Arrays.toString(addresses));
		LOG.info("Melding lagres som: " + destFile);

		Address[] realAddresses = null;
		if (realSend)
			realAddresses = config.filter(addresses);

		try {
			fos = new FileOutputStream(destFile);
			final PrintWriter w = new PrintWriter(fos, false);

			if (realAddresses == null || realAddresses.length == 0)
				w.println("Meldingen er IKKE SENDT til noen.");
			else {
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
			notifyTransportListeners(TransportEvent.MESSAGE_DELIVERED,
					addresses, null, null, message);
		} catch (final FileNotFoundException e) {
			LOG.warn("Filnavn: " + destFile, e);
			notifyTransportListeners(TransportEvent.MESSAGE_NOT_DELIVERED,
					addresses, null, null, message);
		} catch (final IOException e) {
			LOG.warn("Filnavn: " + destFile, e);
			notifyTransportListeners(
					TransportEvent.MESSAGE_PARTIALLY_DELIVERED, addresses,
					null, null, message);
		}

		if (realSend)
			originalTransport.sendMessage(message, realAddresses);

	}

	private String subjectForName(final String subject, final int maxLen) {
		if (subject == null || subject.length() == 0)
			return "";
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
					if (b == 0 && c != '_')
						cb[b++] = '_';
					cb[b++] = c;
					if (b >= l)
						break;
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

	private Config getConfig() throws IOException {
		Config config;
		final ClassLoader cl = Thread.currentThread().getContextClassLoader();
		synchronized (clMap) {
			config = clMap.get(cl);
			if (config == null) {
				config = Config.createConfig(cl, "debugMail.properties");
				if (config == null)
					config = Config.NULL;
				clMap.put(cl, config);
			}
		}
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
	public synchronized void addTransportListener(final TransportListener arg0) {
		if (realSend)
			originalTransport.addTransportListener(arg0);
		else
			super.addTransportListener(arg0);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.mail.Transport#removeTransportListener(javax.mail.event.
	 * TransportListener)
	 */
	@Override
	public synchronized void removeTransportListener(
			final TransportListener arg0) {
		if (realSend)
			originalTransport.removeTransportListener(arg0);
		else
			super.removeTransportListener(arg0);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * javax.mail.Service#addConnectionListener(javax.mail.event.ConnectionListener
	 * )
	 */
	@Override
	public synchronized void addConnectionListener(final ConnectionListener arg0) {
		if (realSend)
			originalTransport.addConnectionListener(arg0);
		else
			super.addConnectionListener(arg0);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.mail.Service#close()
	 */
	@Override
	public synchronized void close() throws MessagingException {
		if (realSend)
			originalTransport.close();
		else
			super.close();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.mail.Service#connect()
	 */
	@Override
	public void connect() throws MessagingException {
		if (realSend)
			originalTransport.connect();
		else
			super.connect();
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
		if (realSend)
			originalTransport.connect(arg0, arg1, arg2, arg3);
		else
			super.connect(arg0, arg1, arg2, arg3);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.mail.Service#connect(java.lang.String, java.lang.String,
	 * java.lang.String)
	 */
	@Override
	public void connect(final String arg0, final String arg1, final String arg2)
			throws MessagingException {
		if (realSend)
			originalTransport.connect(arg0, arg1, arg2);
		else
			super.connect(arg0, arg1, arg2);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.mail.Service#getURLName()
	 */
	@Override
	public URLName getURLName() {
		if (realSend)
			return originalTransport.getURLName();
		return super.getURLName();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.mail.Service#isConnected()
	 */
	@Override
	public boolean isConnected() {
		if (realSend)
			return originalTransport.isConnected();
		return super.isConnected();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.mail.Service#removeConnectionListener(javax.mail.event.
	 * ConnectionListener)
	 */
	@Override
	public synchronized void removeConnectionListener(
			final ConnectionListener arg0) {
		if (realSend)
			originalTransport.removeConnectionListener(arg0);
		else
			super.removeConnectionListener(arg0);
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
			final String user, final String password) throws MessagingException {
		return baseDir != null;
	}

	static class Config {

		private final List<Pattern> rules = new ArrayList<Pattern>();
		private final List<InternetAddress> replacements = new ArrayList<InternetAddress>();
		static final Config NULL = new Config();

		Address[] filter(final Address[] addresses) {
			final List<Address> list = new ArrayList<Address>();
			if (rules.isEmpty())
				list.addAll(replacements);
			else {
				final Set<String> set = new HashSet<String>();
				for (final Address a : addresses)
					if (a instanceof InternetAddress) {
						final InternetAddress ia = (InternetAddress) a;
						final String address = ia.getAddress().toLowerCase();
						if (!set.contains(address))
							for (final Pattern allow : rules)
								if (allow.matcher(address).matches()) {
									set.add(address);
									list.add(a);
									break;
								}
					}
				for (final InternetAddress a : replacements) {
					final String e = a.getAddress().toLowerCase();
					if (set.add(e))
						list.add(a);
				}
			}
			return list.toArray(new Address[list.size()]);
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

		public static Config createConfig(final ClassLoader classLoader,
				final String propertyfile) throws IOException {
			Config config = null;
			final URL resource = classLoader.getResource(propertyfile);
			if (resource != null) {
				final InputStream openStream = resource.openStream();
				final Properties p = new Properties();
				try {
					p.load(openStream);
				} finally {
					try {
						openStream.close();
					} catch (final IOException e) {
					}
				}
				final String allows = p.getProperty("allow");
				if (allows != null) {
					final String[] allowRules = COMMASPACE.split(allows);
					for (final String allowRule : allowRules)
						if (allowRule.length() > 0) {
							Pattern rule;
							try {
								rule = Pattern.compile(allowRule);
								if (config == null)
									config = new Config();
								config.addRule(rule);
							} catch (final Exception e) {
								log.warn("allowRule: " + allowRule, e);
							}
						}
				}
				final String replace = p.getProperty("replace");
				if (replace != null) {
					final String[] replacements = COMMASPACE.split(replace);
					for (final String replacement : replacements) {
						final String[] parts = BAR.split(replacement);
						final InternetAddress address = new InternetAddress();
						String addr;
						if (parts.length > 1) {
							addr = parts[1].trim();
							address.setPersonal(parts[0].trim());
						} else
							addr = parts[0].trim();
						if (addr.length() == 0)
							continue;
						address.setAddress(addr);
						if (config == null)
							config = new Config();
						config.addReplacement(address);
					}
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
					if (b.length() > mark)
						b.append(", ");
					b.append(pattern.toString());
				}
				b.append(']');
			}
			if (replacementsList.size() > 0) {
				if (b.length() > 0)
					b.append(", ");
				b.append("replace=[");
				final int mark = b.length();
				for (final InternetAddress internetAddress : replacementsList) {
					if (b.length() > mark)
						b.append(", ");
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