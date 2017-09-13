package com.github.sergiofgonzalez.wconf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import static com.github.sergiofgonzalez.wconf.MetaConfigKeys.*;

/**
 * Eagerly loaded Singleton supporting wconf()
 * 
 * Use wconf() to access the API that provide access to the 
 *
 */
public class WaterfallConfig {
	
	private static Logger LOGGER = LoggerFactory.getLogger(WaterfallConfig.class);
	
	private static WaterfallConfig uniqueInstance = new WaterfallConfig();
	
	private UUID instanceUUID;
	
	private boolean isEncryptionEnabled;
	private Config config;
	private Cipher cipher;
		
	private String REFERENCE_RESOURCE = "config/common.conf";
	private String DEFAULT_APPLICATION_RESOURCE = "config/application.conf";

	
	private WaterfallConfig() {
		Instant start = Instant.now();
		instanceUUID = UUID.randomUUID();
		LOGGER.debug("Initializing a new Waterfall config: {}", instanceUUID = UUID.randomUUID());

		/* Load common props as a resource: It's assumed it's found inside the JAR */
		Config commonProps = ConfigFactory.parseResourcesAnySyntax(REFERENCE_RESOURCE).resolve();
		
		/* Compute app resource name */
		Config tempConfig = commonProps
								.withFallback(ConfigFactory.systemEnvironment())
								.withFallback(ConfigFactory.systemProperties());
		String appResource = tempConfig.hasPath(META_CONFIG_APP_RESOURCE_KEY.toString())? tempConfig.getString(META_CONFIG_APP_RESOURCE_KEY.toString()) : DEFAULT_APPLICATION_RESOURCE;
		String externalAppResource = Paths.get(appResource).getFileName().toString();
		
		/* Load props found outside the JAR */
		Config applicationPropsFoundOutsideJar = ConfigFactory.parseFile(Paths.get(externalAppResource).toFile());
		
		/* Load props from environment vars */
		Config environmentVariablesProps = ConfigFactory.systemEnvironment();
		
		/* Load props from Java system vars */
		Config javaSystemProps = ConfigFactory.systemProperties();
		
		/* Load props from app resource inside the Jar */
		Config applicationProps = ConfigFactory.parseResources(appResource).resolve();
		
		/* Establish precedence rules */
		Config conf = applicationPropsFoundOutsideJar
						.withFallback(environmentVariablesProps)
						.withFallback(javaSystemProps)
						.withFallback(applicationProps)
						.withFallback(commonProps);
		
		/* restrict conf properties to the specified profile if found */
		if (conf.hasPath(META_CONFIG_ACTIVE_PROFILE_KEY.toString())) {
			String activeProfile = conf.getString(META_CONFIG_ACTIVE_PROFILE_KEY.toString());
			
			
			if (applicationPropsFoundOutsideJar.hasPath(activeProfile)) {
				config = applicationPropsFoundOutsideJar.getConfig(activeProfile)
							.withFallback(environmentVariablesProps)
							.withFallback(javaSystemProps);
			} else {
				config = environmentVariablesProps
							.withFallback(javaSystemProps);
			}
			
			if (applicationProps.hasPath(activeProfile)) {
				config = config
							.withFallback(applicationProps.getConfig(activeProfile));
			}

			config = config
						.withFallback(commonProps);

		} else {
			config = conf;
		}

		
		
		/* Apply encryption if enabled */
		isEncryptionEnabled = config.hasPath(META_CONFIG_ENCRYPTION_SWITCH_KEY.toString()) ? config.getBoolean(META_CONFIG_ENCRYPTION_SWITCH_KEY.toString()) : false;
		
		if (isEncryptionEnabled) {
			String encryptionAlgorithm = config.getString(META_CONFIG_ENCRYPTION_ALGORITHM_KEY.toString());
			String keyType = config.getString(META_CONFIG_ENCRYPTION_KEY_TYPE_KEY.toString());
			String keystorePath = config.getString(META_CONFIG_ENCRYPTION_KEY_STORE_PATH_KEY.toString());
			String keyStorePassword = config.getString(META_CONFIG_ENCRYPTION_KEY_STORE_PASSWORD_KEY.toString());
			String configSecretKeyAlias = config.getString(META_CONFIG_ENCRYPTION_KEY_STORE_KEY_ALIAS_KEY.toString());
			String configSecretKeyPassword = config.getString(META_CONFIG_ENCRYPTION_KEY_STORE_KEY_PASSWORD_KEY.toString());
			String encodedInitializationVector = config.getString(META_CONFIG_ENCRYPTION_IV_KEY.toString());
		
			try (InputStream keystoreStream = WaterfallConfig.class.getClassLoader().getResourceAsStream(keystorePath)) {
				KeyStore keyStore = KeyStore.getInstance("JCEKS");
				keyStore.load(keystoreStream, keyStorePassword.toCharArray());
				
				if (!keyStore.containsAlias(configSecretKeyAlias)) {
					LOGGER.error("The key {} was not found in the key store {}", configSecretKeyAlias, keyStore);
					throw new IllegalStateException("Could not found the expected key in the provided keystore");
				}
				
				Key aesKey = keyStore.getKey(configSecretKeyAlias, configSecretKeyPassword.toCharArray());	
				
				SecretKeySpec secretKeySpec = new SecretKeySpec(aesKey.getEncoded(), keyType);
				cipher = Cipher.getInstance(encryptionAlgorithm);
				IvParameterSpec ivParameterSpec = new IvParameterSpec(Base64.getDecoder().decode(encodedInitializationVector));
				
				cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
				
			} catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException | UnrecoverableKeyException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException e) {
				LOGGER.error("Could not initialize the encryption scheme from the provided keystore and config data", e);
				throw new IllegalStateException("Could not initialize the encryption scheme", e);
			}			
		}
				
		Duration duration = Duration.between(start, Instant.now());
		LOGGER.debug("Config initialization took {}", duration);
		LOGGER.debug("Encryption configured: {}", isEncryptionEnabled);
	}
	
	/**
	 * Access method to the configuration object
	 *  
	 * @return an instance of the configuration object that provide access to the configuration properties
	 */
	public static WaterfallConfig wconf() {
		return uniqueInstance;
	}
	
	/**
	 * Obtains the string representation of a configuration parameter.
	 * 
	 * @param key the key of the configuration parameter to retrieve
	 * @return the string value associated to the key
	 */
	public String get(String key) {
		Instant start = Instant.now();
		String value = uniqueInstance.config.getString(key);
	
		if (value.startsWith("cipher(") && value.endsWith(")")) {
			if (!isEncryptionEnabled) {
				throw new IllegalStateException("Encryption has not been enabled.");
			}
			String cipherText = value.substring(7, value.length() - 1);
			byte[] clearBytes;
			try {
				clearBytes = cipher.doFinal(Base64.getDecoder().decode(cipherText));
			} catch (IllegalBlockSizeException | BadPaddingException e) {
				LOGGER.error("Error trying to decrypt key {}", key);
				throw new IllegalArgumentException("Could not decrypt config value", e);
			}
			value = new String(clearBytes, StandardCharsets.UTF_8);
		}
		LOGGER.debug("Access to config {} to read {} took {}", uniqueInstance.instanceUUID, key, Duration.between(start, Instant.now()));		
		return value;
	}
	
	/**
	 * <b>Experimental</b>
	 * Obtains the list of strings for a given configuration property key 
	 * @param key the property key of the value to retrieved
	 * @param isMultivalued a dummy parameter used to indicate we're interested in a multivalued property
	 * @return the list of values associated to the given property key
	 */
	public List<String> get(String key, boolean isMultivalued) {
		Instant start = Instant.now();
		List<String> values = uniqueInstance.config.getStringList(key);
		LOGGER.debug("Access to config {} took {}", uniqueInstance.instanceUUID, Duration.between(start, Instant.now()));
		return values;
	}
	
}
