package xdb.jpa;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.*;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.jpa.repository.config.JpaRepositoryConfigExtension;
import org.springframework.data.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
	* @author <a href="mailto:josh@joshlong.com">Josh Long</a>
	*/
@Log4j2
@Configuration
class MultipleJpaRegistrationImportBeanDefinitionRegistrar implements
	ApplicationContextAware, ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware {

		private Environment environment;
		private ResourceLoader resourceLoader;
		private ApplicationContext applicationContext;

		@Override
		public void setEnvironment(Environment environment) {
				this.environment = environment;
		}

		@Override
		public void setResourceLoader(ResourceLoader resourceLoader) {
				this.resourceLoader = resourceLoader;
		}

		@Override
		public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
				this.applicationContext = applicationContext;
		}

		@Override
		public void registerBeanDefinitions(AnnotationMetadata importingClassAnnotationMetadata, BeanDefinitionRegistry registry) {

				DefaultListableBeanFactory defaultListableBeanFactory = DefaultListableBeanFactory.class.cast(registry);

				if (this.applicationContext == null && registry instanceof AutowireCapableBeanFactory) {
						AutowireCapableBeanFactory.class.cast(registry).initializeBean(this, this.getClass().getSimpleName());
				}

				Assert.notNull(this.applicationContext, "the " + ApplicationContext.class.getName() + " reference can't be null!");

				ApplicationContextAwareBinderSupplier binderSupplier = new ApplicationContextAwareBinderSupplier(this.applicationContext);

				String enableMultipleJpaRegistrationsAnnotationName = EnableMultipleJpaRegistrations.class.getName();

				importingClassAnnotationMetadata
					.getAllAnnotationAttributes(enableMultipleJpaRegistrationsAnnotationName)
					.get("value")
					.stream()
					.map(o -> (AnnotationAttributes[]) o)
					.flatMap(Stream::of)
					.forEach(jpaRegistration -> {

							String prefix = jpaRegistration.getString("prefix");
							Class<?> rootPackageClass = jpaRegistration.getClass("rootPackageClass");
							String rootPackage = jpaRegistration.getString("rootPackage");

							String resolvedPackage = (StringUtils.hasText(rootPackage)) ? rootPackage : rootPackageClass.getPackage().getName();
							Assert.hasText(resolvedPackage, "you must specify a root package when using " + enableMultipleJpaRegistrationsAnnotationName);
							this
								.registerJpaRegistrationsForPrefixAndPackage(
									defaultListableBeanFactory,
									binderSupplier,
									prefix,
									resolvedPackage
								);
					});
		}

		protected <T> String registerLazyInfrastructureBeanForLabel(
			String label,
			String newBeanName,
			Class<T> clzz,
			BeanDefinitionRegistry beanDefinitionRegistry,
			Supplier<T> supplier) {

				if (beanDefinitionRegistry.containsBeanDefinition(newBeanName)) {
						BeanDefinition beanDefinition = beanDefinitionRegistry.getBeanDefinition(newBeanName);
						if (beanDefinition.getBeanClassName().equals(clzz.getName())) {
								log.warn(beanDefinitionRegistry.getClass().getName() + " already contains a bean of name '" +
									newBeanName + "' of class '" + beanDefinition.getBeanClassName() + "'");
						}
						return newBeanName;
				}

				GenericBeanDefinition gdb = new GenericBeanDefinition();
				gdb.setBeanClass(clzz);
				gdb.setSynthetic(true);
				gdb.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
				gdb.setInstanceSupplier(supplier);
				gdb.setLazyInit(true);
				gdb.addQualifier(new AutowireCandidateQualifier(Qualifier.class, label));
				beanDefinitionRegistry.registerBeanDefinition(newBeanName, gdb);
				log.debug("adding qualifier '" + label + "' for " + clzz.getName() + " instance.");
				log.debug("registered bean " + newBeanName + ".");
				return newBeanName;
		}


		protected void registerJpaRepositories(
			String pkg, String transactionManagerBeanName, String entityManagerBeanName,
			BeanDefinitionRegistry beanDefinitionRegistry) {

				RepositoryConfigurationSourceSupport configurationSource = new DynamicRepositoryConfigurationSourceSupport(
					this.environment, getClass().getClassLoader(), beanDefinitionRegistry, pkg, transactionManagerBeanName, entityManagerBeanName);

				RepositoryConfigurationExtension extension = new JpaRepositoryConfigExtension();
				RepositoryConfigurationUtils.exposeRegistration(extension, beanDefinitionRegistry, configurationSource);

				extension.registerBeansForRoot(beanDefinitionRegistry, configurationSource);

				VisibleRepositoryBeanDefinitionBuilder builder = new VisibleRepositoryBeanDefinitionBuilder(beanDefinitionRegistry, extension, this.resourceLoader, this.environment);

				for (RepositoryConfiguration<? extends RepositoryConfigurationSource> configuration : extension.getRepositoryConfigurations(configurationSource, resourceLoader, true)) {

						BeanDefinitionBuilder definitionBuilder = builder.build(configuration);
						extension.postProcess(definitionBuilder, configurationSource);
						definitionBuilder.addPropertyValue("enableDefaultTransactions", false);

						AbstractBeanDefinition beanDefinition = definitionBuilder.getBeanDefinition();
						String beanName = configurationSource.generateBeanName(beanDefinition);

						log
							.debug("Spring Data {} - Registering repository: {} - Interface: {} - Factory: {}",
								extension.getModuleName(), beanName, configuration.getRepositoryInterface(), configuration.getRepositoryFactoryBeanClassName());

						beanDefinition.setAttribute("factoryBeanObjectType", configuration.getRepositoryInterface());
						beanDefinitionRegistry.registerBeanDefinition(beanName, beanDefinition);
				}
		}

		static class JpaRegistrationConfigurerSupplier implements Supplier<DataSource> {

				private final DefaultListableBeanFactory defaultListableBeanFactory;
				private final String label;
				private final String jpaRegistrationConfigurerBeanName;

				JpaRegistrationConfigurerSupplier(String label, String jparcbn, DefaultListableBeanFactory defaultListableBeanFactory) {
						this.defaultListableBeanFactory = defaultListableBeanFactory;
						this.jpaRegistrationConfigurerBeanName = jparcbn;
						this.label = label;
				}

				@Override
				public DataSource get() {

						JpaRegistrationConfigurer configurer = this.defaultListableBeanFactory
							.getBean(this.jpaRegistrationConfigurerBeanName, JpaRegistrationConfigurer.class);

						return configurer.getDataSourceFor(this.label);
				}
		}

		protected void registerJpaRegistrationsForPrefixAndPackage(
			DefaultListableBeanFactory registry,
			Supplier<Binder> binderSupplier,
			String label,
			String packageName) {

				BoundDataSourceRegistrationSupplier dsrSupplier = new BoundDataSourceRegistrationSupplier(binderSupplier, label);

				// DataSourceRegistration
				this.registerLazyInfrastructureBeanForLabel(label, label + DataSourceRegistration.class.getSimpleName(), DataSourceRegistration.class, registry, dsrSupplier);

				// DataSource
				String dataSourceBeanName = label + DataSource.class.getSimpleName();

				String jpaConfigurerBeanNames[];
				Supplier<DataSource> dataSourceSupplier;
				if ((jpaConfigurerBeanNames = registry.getBeanNamesForType(JpaRegistrationConfigurer.class)).length > 0) {
						Assert.isTrue(jpaConfigurerBeanNames.length == 1, "there should only be one instance of " + JpaRegistrationConfigurer.class.getName());
						dataSourceSupplier = new JpaRegistrationConfigurerSupplier(label, jpaConfigurerBeanNames[0], registry);
				}
				else {
						dataSourceSupplier = new BoundDataSourceSupplier(dsrSupplier);
				}
				this.registerLazyInfrastructureBeanForLabel(label, dataSourceBeanName, DataSource.class, registry, dataSourceSupplier);

				// JdbcTemplate
				this.registerLazyInfrastructureBeanForLabel(label, label + JdbcTemplate.class.getSimpleName(), JdbcTemplate.class, registry, new JdbcTemplateSupplier(registry, dataSourceBeanName));

				// JPA EntityManagerFactory
				EntityManagerFactorySupplier emfSupplier = new EntityManagerFactorySupplier(registry, this.resourceLoader, dataSourceBeanName, packageName);
				Class<EntityManagerFactory> entityManagerFactoryClass = EntityManagerFactory.class;
				String jpaEntityManagerFactoryBeanName = label + entityManagerFactoryClass.getSimpleName();
				this.registerLazyInfrastructureBeanForLabel(label, jpaEntityManagerFactoryBeanName, entityManagerFactoryClass, registry, emfSupplier);

				// JPA TransactionManager
				JpaTransactionManagerSupplier jpaTransactionManagerSupplier = new JpaTransactionManagerSupplier(registry, jpaEntityManagerFactoryBeanName);
				String jpaTransactionManagerBeanName = label + JpaTransactionManager.class.getName();
				this.registerLazyInfrastructureBeanForLabel(label, jpaTransactionManagerBeanName, JpaTransactionManager.class, registry, jpaTransactionManagerSupplier);

				// JPA TransactionTemplate
				this.registerLazyInfrastructureBeanForLabel(label, label + TransactionTemplate.class.getSimpleName(),
					TransactionTemplate.class, registry, () -> new TransactionTemplate(
						registry.getBean(jpaTransactionManagerBeanName, JpaTransactionManager.class)
					));

				// Spring Data JPA
				this.registerJpaRepositories(packageName, jpaTransactionManagerBeanName, jpaEntityManagerFactoryBeanName, registry);
		}
}
