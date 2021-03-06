/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.archaius;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PreDestroy;

import lombok.extern.apachecommons.CommonsLog;
import org.apache.commons.configuration.ConfigurationBuilder;
import org.apache.commons.configuration.EnvironmentConfiguration;
import org.apache.commons.configuration.SystemConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.Endpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ReflectionUtils;

import com.netflix.config.ConcurrentCompositeConfiguration;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicURLConfiguration;

import static com.netflix.config.ConfigurationBasedDeploymentContext.DEPLOYMENT_APPLICATION_ID_PROPERTY;
import static com.netflix.config.ConfigurationManager.APPLICATION_PROPERTIES;
import static com.netflix.config.ConfigurationManager.DISABLE_DEFAULT_ENV_CONFIG;
import static com.netflix.config.ConfigurationManager.DISABLE_DEFAULT_SYS_CONFIG;
import static com.netflix.config.ConfigurationManager.ENV_CONFIG_NAME;
import static com.netflix.config.ConfigurationManager.SYS_CONFIG_NAME;
import static com.netflix.config.ConfigurationManager.URL_CONFIG_NAME;

/**
 * @author Spencer Gibb
 */
@Configuration
@ConditionalOnClass({ ConcurrentCompositeConfiguration.class, ConfigurationBuilder.class })
@CommonsLog
public class ArchaiusAutoConfiguration {

	private static final AtomicBoolean initialized = new AtomicBoolean(false);

	@Autowired
	private ConfigurableEnvironment env;

	@PreDestroy
	public void close() {
		setStatic(ConfigurationManager.class, "instance", null);
		setStatic(ConfigurationManager.class, "customConfigurationInstalled", false);
		setStatic(DynamicPropertyFactory.class, "config", null);
		setStatic(DynamicPropertyFactory.class, "initializedWithDefaultConfig", false);
		initialized.compareAndSet(true, false);
	}

	@Bean
	public ConfigurableEnvironmentConfiguration configurableEnvironmentConfiguration() {
		ConfigurableEnvironmentConfiguration envConfig = new ConfigurableEnvironmentConfiguration(
				this.env);
		configureArchaius(envConfig);
		return envConfig;
	}

	@Configuration
	@ConditionalOnClass(Endpoint.class)
	protected static class ArchaiusEndpointConfuguration {
		@Bean
		protected ArchaiusEndpoint archaiusEndpoint() {
			return new ArchaiusEndpoint();
		}
	}

	@SuppressWarnings("deprecation")
	protected void configureArchaius(ConfigurableEnvironmentConfiguration envConfig) {
		if (initialized.compareAndSet(false, true)) {
			String appName = this.env.getProperty("spring.application.name");
			if (appName == null) {
				appName = "application";
				log.warn("No spring.application.name found, defaulting to 'application'");
			}
			// this is deprecated, but currently it seams the only way to set it initially
			System.setProperty(DEPLOYMENT_APPLICATION_ID_PROPERTY, appName);

			// TODO: support for other DeploymentContexts

			ConcurrentCompositeConfiguration config = new ConcurrentCompositeConfiguration();

			// support to add other Configurations (Jdbc, DynamoDb, Zookeeper, jclouds,
			// etc...)
			/*
			 * if (factories != null && !factories.isEmpty()) { for
			 * (PropertiesSourceFactory factory: factories) {
			 * config.addConfiguration(factory.getConfiguration(), factory.getName()); } }
			 */
			config.addConfiguration(envConfig,
					ConfigurableEnvironmentConfiguration.class.getSimpleName());

			// below come from ConfigurationManager.createDefaultConfigInstance()
			DynamicURLConfiguration defaultURLConfig = new DynamicURLConfiguration();
			try {
				config.addConfiguration(defaultURLConfig, URL_CONFIG_NAME);
			}
			catch (Throwable ex) {
				log.error("Cannot create config from " + defaultURLConfig, ex);
			}

			// TODO: sys/env above urls?
			if (!Boolean.getBoolean(DISABLE_DEFAULT_SYS_CONFIG)) {
				SystemConfiguration sysConfig = new SystemConfiguration();
				config.addConfiguration(sysConfig, SYS_CONFIG_NAME);
			}
			if (!Boolean.getBoolean(DISABLE_DEFAULT_ENV_CONFIG)) {
				EnvironmentConfiguration environmentConfiguration = new EnvironmentConfiguration();
				config.addConfiguration(environmentConfiguration, ENV_CONFIG_NAME);
			}

			ConcurrentCompositeConfiguration appOverrideConfig = new ConcurrentCompositeConfiguration();
			config.addConfiguration(appOverrideConfig, APPLICATION_PROPERTIES);
			config.setContainerConfigurationIndex(config
					.getIndexOfConfiguration(appOverrideConfig));

			ConfigurationManager.install(config);
		}
		else {
			// TODO: reinstall ConfigurationManager
			log.warn("Netflix ConfigurationManager has already been installed, unable to re-install");
		}
	}

	private static void setStatic(Class<?> type, String name, Object value) {
		// Hack a private static field
		Field field = ReflectionUtils.findField(type, name);
		ReflectionUtils.makeAccessible(field);
		ReflectionUtils.setField(field, null, value);
	}

}
