/*
 * Copyright 2012-2016 the original author or authors.
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

package org.springframework.boot.autoconfigure.web;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.autoconfigure.template.TemplateAvailabilityProvider;
import org.springframework.boot.context.embedded.EmbeddedServletContainerCustomizer;
import org.springframework.boot.web.servlet.ErrorPage;
import org.springframework.boot.web.servlet.ErrorPageRegistrar;
import org.springframework.boot.web.servlet.ErrorPageRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.expression.MapAccessor;
import org.springframework.core.Ordered;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.PropertyPlaceholderHelper.PlaceholderResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.BeanNameViewResolver;
import org.springframework.web.util.HtmlUtils;

/**
 * {@link EnableAutoConfiguration Auto-configuration} to render errors via an MVC error
 * controller.
 *
 * @author Dave Syer
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 */
@ConditionalOnClass({ Servlet.class, DispatcherServlet.class })
@ConditionalOnWebApplication
// Ensure this loads before the main WebMvcAutoConfiguration so that the error View is
// available
@AutoConfigureBefore(WebMvcAutoConfiguration.class)
@Configuration
public class ErrorMvcAutoConfiguration {

	private final ServerProperties properties;

	public ErrorMvcAutoConfiguration(ServerProperties properties) {
		this.properties = properties;
	}

	@Bean
	@ConditionalOnMissingBean(value = ErrorAttributes.class, search = SearchStrategy.CURRENT)
	public DefaultErrorAttributes errorAttributes() {
		return new DefaultErrorAttributes();
	}

	@Bean
	@ConditionalOnMissingBean(value = ErrorController.class, search = SearchStrategy.CURRENT)
	public BasicErrorController basicErrorController(ErrorAttributes errorAttributes) {
		return new BasicErrorController(errorAttributes, this.properties.getError());
	}

	@Bean
	public ErrorPageCustomizer errorPageCustomizer() {
		return new ErrorPageCustomizer(this.properties);
	}

	@Bean
	public static PreserveErrorControllerTargetClassPostProcessor preserveErrorControllerTargetClassPostProcessor() {
		return new PreserveErrorControllerTargetClassPostProcessor();
	}

	@Configuration
	@ConditionalOnProperty(prefix = "server.error.whitelabel", name = "enabled", matchIfMissing = true)
	@Conditional(ErrorTemplateMissingCondition.class)
	protected static class WhitelabelErrorViewConfiguration {

		private final SpelView defaultErrorView = new SpelView(
				"<html><body><h1>Whitelabel Error Page</h1>"
						+ "<p>This application has no explicit mapping for /error, so you are seeing this as a fallback.</p>"
						+ "<div id='created'>${timestamp}</div>"
						+ "<div>There was an unexpected error (type=${error}, status=${status}).</div>"
						+ "<div>${message}</div></body></html>");

		@Bean(name = "error")
		@ConditionalOnMissingBean(name = "error")
		public View defaultErrorView() {
			return this.defaultErrorView;
		}

		// If the user adds @EnableWebMvc then the bean name view resolver from
		// WebMvcAutoConfiguration disappears, so add it back in to avoid disappointment.
		@Bean
		@ConditionalOnMissingBean(BeanNameViewResolver.class)
		public BeanNameViewResolver beanNameViewResolver() {
			BeanNameViewResolver resolver = new BeanNameViewResolver();
			resolver.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
			return resolver;
		}

	}

	/**
	 * {@link SpringBootCondition} that matches when no error template view is detected.
	 */
	private static class ErrorTemplateMissingCondition extends SpringBootCondition {

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context,
				AnnotatedTypeMetadata metadata) {
			List<TemplateAvailabilityProvider> availabilityProviders = SpringFactoriesLoader
					.loadFactories(TemplateAvailabilityProvider.class,
							context.getClassLoader());

			for (TemplateAvailabilityProvider availabilityProvider : availabilityProviders) {
				if (availabilityProvider.isTemplateAvailable("error",
						context.getEnvironment(), context.getClassLoader(),
						context.getResourceLoader())) {
					return ConditionOutcome.noMatch("Template from "
							+ availabilityProvider + " found for error view");
				}
			}

			return ConditionOutcome.match("No error template view detected");
		}

	}

	/**
	 * Simple {@link View} implementation that resolves variables as SpEL expressions.
	 */
	private static class SpelView implements View {

		private final NonRecursivePropertyPlaceholderHelper helper;

		private final String template;

		private final Map<String, Expression> expressions;

		SpelView(String template) {
			this.helper = new NonRecursivePropertyPlaceholderHelper("${", "}");
			this.template = template;
			ExpressionCollector expressionCollector = new ExpressionCollector();
			this.helper.replacePlaceholders(this.template, expressionCollector);
			this.expressions = expressionCollector.getExpressions();
		}

		@Override
		public String getContentType() {
			return "text/html";
		}

		@Override
		public void render(Map<String, ?> model, HttpServletRequest request,
				HttpServletResponse response) throws Exception {
			if (response.getContentType() == null) {
				response.setContentType(getContentType());
			}
			Map<String, Object> map = new HashMap<String, Object>(model);
			map.put("path", request.getContextPath());
			PlaceholderResolver resolver = new ExpressionResolver(this.expressions, map);
			String result = this.helper.replacePlaceholders(this.template, resolver);
			response.getWriter().append(result);
		}

	}

	/**
	 * {@link PlaceholderResolver} to collect placeholder expressions.
	 */
	private static class ExpressionCollector implements PlaceholderResolver {

		private final SpelExpressionParser parser = new SpelExpressionParser();

		private final Map<String, Expression> expressions = new HashMap<String, Expression>();

		@Override
		public String resolvePlaceholder(String name) {
			this.expressions.put(name, this.parser.parseExpression(name));
			return null;
		}

		public Map<String, Expression> getExpressions() {
			return Collections.unmodifiableMap(this.expressions);
		}

	}

	/**
	 * SpEL based {@link PlaceholderResolver}.
	 */
	private static class ExpressionResolver implements PlaceholderResolver {

		private final Map<String, Expression> expressions;

		private final EvaluationContext context;

		ExpressionResolver(Map<String, Expression> expressions, Map<String, ?> map) {
			this.expressions = expressions;
			this.context = getContext(map);
		}

		private EvaluationContext getContext(Map<String, ?> map) {
			StandardEvaluationContext context = new StandardEvaluationContext();
			context.addPropertyAccessor(new MapAccessor());
			context.setRootObject(map);
			return context;
		}

		@Override
		public String resolvePlaceholder(String placeholderName) {
			Expression expression = this.expressions.get(placeholderName);
			return escape(expression == null ? null : expression.getValue(this.context));
		}

		private String escape(Object value) {
			return HtmlUtils.htmlEscape(value == null ? null : value.toString());
		}

	}

	/**
	 * {@link EmbeddedServletContainerCustomizer} that configures the container's error
	 * pages.
	 */
	private static class ErrorPageCustomizer implements ErrorPageRegistrar, Ordered {

		private final ServerProperties properties;

		protected ErrorPageCustomizer(ServerProperties properties) {
			this.properties = properties;
		}

		@Override
		public void registerErrorPages(ErrorPageRegistry errorPageRegistry) {
			ErrorPage errorPage = new ErrorPage(this.properties.getServletPrefix()
					+ this.properties.getError().getPath());
			errorPageRegistry.addErrorPages(errorPage);
		}

		@Override
		public int getOrder() {
			return 0;
		}

	}

	/**
	 * {@link BeanFactoryPostProcessor} to ensure that the target class of ErrorController
	 * MVC beans are preserved when using AOP.
	 */
	static class PreserveErrorControllerTargetClassPostProcessor
			implements BeanFactoryPostProcessor {

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
				throws BeansException {
			String[] errorControllerBeans = beanFactory
					.getBeanNamesForType(ErrorController.class, false, false);
			for (String errorControllerBean : errorControllerBeans) {
				try {
					beanFactory.getBeanDefinition(errorControllerBean).setAttribute(
							AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
				}
				catch (Throwable ex) {
					// Ignore
				}
			}
		}

	}

}
