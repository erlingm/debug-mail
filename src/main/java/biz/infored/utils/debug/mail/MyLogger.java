package biz.infored.utils.debug.mail;

import java.lang.reflect.Method;

public class MyLogger {

	private static final Method warn;
	static {
		try {
			Class<?> loggerClass = Class.forName("org.slf4j.Logger");
		} catch (ClassNotFoundException e) {
		}
		warn = null;
	}
}