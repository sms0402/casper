package com.test.dt.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

public class JobConfigProperties extends Properties {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	final static String DEFAULT_PROPERTIES_FILE = "IDC_DT.conf";
	private JobConfigProperties instance = null;
	private Properties props = null;
	
	public JobConfigProperties() {
		
	}
	
	/**
	 * create JobConfigProperties single-ton class 
	 * @param proertiesFile
	 * @return
	 * @throws IOException
	 */
	public JobConfigProperties getInstance(String proertiesFile) throws IOException {
		if(instance == null) {
			instance = new JobConfigProperties(proertiesFile);
		}
		return instance;
	}
	
	public String get(String key) {
		return new String(props.getProperty(key));
	}
	
	public String getOrElse(String key, String defaultValue) {
		
		return new String(props.getProperty(key));
	}
	
	/**
	 * constructor 
	 * @param proertiesFile
	 * @throws IOException
	 */
	public JobConfigProperties(String proertiesFile) {
		loadPropertiesFile(proertiesFile);
	}
	
	/**
	 * 
	 * @param propertiesFile
	 * @return
	 * @throws IOException
	 */
	private Properties loadPropertiesFile(String propertiesFile) {
		props = new Properties();
		File propsFile = null;
		if (propertiesFile != null) {
			propsFile = new File(propertiesFile);
		} else {
			return null;
		}

		if (propsFile.isFile()) {
			FileInputStream fd = null;
			try {
				fd = new FileInputStream(propsFile);
				props.load(new InputStreamReader(fd, StandardCharsets.UTF_8));
				System.out.println("size : " + props.size());
				for (Map.Entry<Object, Object> e : props.entrySet()) {
					System.out.println(e.getKey() + " : " + e.getValue());
					e.setValue(e.getValue().toString().trim());
				}
			} catch(IOException e) {
				
			}finally {
				if (fd != null) {
					try {
						fd.close();
					} catch (IOException e) {
					}
				}
			}
		}

		return props;
	}

}
