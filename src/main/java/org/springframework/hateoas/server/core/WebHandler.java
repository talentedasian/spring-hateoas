/*
 * Copyright 2019-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.hateoas.server.core;

import static org.springframework.hateoas.TemplateVariable.VariableType.*;
import static org.springframework.hateoas.TemplateVariables.*;
import static org.springframework.web.util.UriComponents.UriTemplateVariables.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.hateoas.Affordance;
import org.springframework.hateoas.NonComposite;
import org.springframework.hateoas.TemplateVariable;
import org.springframework.hateoas.TemplateVariables;
import org.springframework.hateoas.server.LinkBuilder;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriTemplate;

/**
 * Utility for taking a method invocation and extracting a {@link LinkBuilder}.
 *
 * @author Greg Turnquist
 * @author Oliver Drotbohm
 */
public class WebHandler {

	public interface LinkBuilderCreator<T extends LinkBuilder> {
		T createBuilder(UriComponents components, TemplateVariables variables, List<Affordance> affordances);
	}

	public interface PreparedWebHandler<T extends LinkBuilder> {
		T conclude(Function<String, UriComponentsBuilder> finisher, ConversionService conversionService);
	}

	public static <T extends LinkBuilder> PreparedWebHandler<T> linkTo(Object invocationValue,
			LinkBuilderCreator<T> creator) {
		return linkTo(invocationValue, creator,
				(BiFunction<UriComponentsBuilder, MethodInvocation, UriComponentsBuilder>) null);
	}

	public static <T extends LinkBuilder> T linkTo(Object invocationValue, LinkBuilderCreator<T> creator,
			@Nullable BiFunction<UriComponentsBuilder, MethodInvocation, UriComponentsBuilder> additionalUriHandler,
			Function<String, UriComponentsBuilder> finisher, Supplier<ConversionService> conversionService) {

		return linkTo(invocationValue, creator, additionalUriHandler).conclude(finisher, conversionService.get());
	}

	private static <T extends LinkBuilder> PreparedWebHandler<T> linkTo(Object invocationValue,
			LinkBuilderCreator<T> creator,
			@Nullable BiFunction<UriComponentsBuilder, MethodInvocation, UriComponentsBuilder> additionalUriHandler) {

		Assert.isInstanceOf(LastInvocationAware.class, invocationValue);

		LastInvocationAware invocations = (LastInvocationAware) DummyInvocationUtils
				.getLastInvocationAware(invocationValue);

		if (invocations == null) {
			throw new IllegalStateException(String.format("Could not obtain previous invocation from %s!", invocationValue));
		}

		MethodInvocation invocation = invocations.getLastInvocation();
		String mapping = SpringAffordanceBuilder.getMapping(invocation.getTargetType(), invocation.getMethod());

		return (finisher, conversionService) -> {

			UriComponentsBuilder builder = finisher.apply(mapping);
			UriTemplate template = UriTemplateFactory.templateFor(mapping == null ? "/" : mapping);
			Map<String, Object> values = new HashMap<>();

			List<String> variableNames = template.getVariableNames();
			Iterator<String> names = variableNames.iterator();
			Iterator<Object> classMappingParameters = invocations.getObjectParameters();

			while (classMappingParameters.hasNext()) {
				String name = names.next();
				TemplateVariable variable = TemplateVariable.segment(name);
				values.put(name, variable.prepareAndEncode(classMappingParameters.next()));
			}

			Method method = invocation.getMethod();
			HandlerMethodParameters parameters = HandlerMethodParameters.of(method);
			Object[] arguments = invocation.getArguments();
			ConversionService resolved = conversionService;

			for (HandlerMethodParameter parameter : parameters.getParameterAnnotatedWith(PathVariable.class, arguments)) {
				TemplateVariable variable = TemplateVariable.segment(parameter.getVariableName());
				values.put(variable.getName(), variable.prepareAndEncode(parameter.getValueAsString(arguments, resolved)));
			}

			List<String> optionalEmptyParameters = new ArrayList<>();

			for (HandlerMethodParameter parameter : parameters.getParameterAnnotatedWith(RequestParam.class, arguments)) {

				bindRequestParameters(builder, parameter, arguments, conversionService);

				boolean isSkipValue = SKIP_VALUE.equals(parameter.getVerifiedValue(arguments));
				boolean isMapParameter = Map.class.isAssignableFrom(parameter.parameter.getParameterType());

				if (isSkipValue && !isMapParameter) {

					values.put(parameter.getVariableName(), SKIP_VALUE);

					if (!parameter.isRequired()) {
						optionalEmptyParameters.add(parameter.getVariableName());
					}
				}
			}

			for (String variable : variableNames) {
				if (!values.containsKey(variable)) {
					values.put(variable, SKIP_VALUE);
				}
			}

			UriComponents components = additionalUriHandler == null //
					? builder.buildAndExpand(values) //
					: additionalUriHandler.apply(builder, invocation).buildAndExpand(values);

			TemplateVariables variables = NONE;

			for (String parameter : optionalEmptyParameters) {

				boolean previousRequestParameter = components.getQueryParams().isEmpty() && variables.equals(NONE);
				TemplateVariable variable = new TemplateVariable(parameter,
						previousRequestParameter ? REQUEST_PARAM : REQUEST_PARAM_CONTINUED);
				variables = variables.concat(variable);
			}

			List<Affordance> affordances = SpringAffordanceBuilder.getAffordances(invocation.getTargetType(), method,
					components.toUriString());

			return creator.createBuilder(components, variables, affordances);
		};
	}

	/**
	 * Populates the given {@link UriComponentsBuilder} with request parameters found in the given
	 * {@link AnnotatedParametersParameterAccessor.BoundMethodParameter}.
	 *
	 * @param builder must not be {@literal null}.
	 * @param parameter must not be {@literal null}.
	 */
	@SuppressWarnings("unchecked")
	private static void bindRequestParameters(UriComponentsBuilder builder, HandlerMethodParameter parameter,
			Object[] arguments, ConversionService conversionService) {

		Object value = parameter.getVerifiedValue(arguments);

		if (value == null) {
			return;
		}

		Class<?> parameterType = parameter.parameter.getParameterType();

		if (value instanceof MultiValueMap) {

			Map<String, List<?>> requestParams = (Map<String, List<?>>) value;

			for (Entry<String, List<?>> entry : requestParams.entrySet()) {
				for (Object element : entry.getValue()) {
					TemplateVariable variable = TemplateVariable.pathVariable(entry.getKey());
					builder.queryParam(entry.getKey(), variable.prepareAndEncode(element));
				}
			}

			return;
		}

		if (value instanceof Map) {

			Map<String, ?> requestParams = (Map<String, ?>) value;

			for (Entry<String, ?> entry : requestParams.entrySet()) {

				String key = entry.getKey();
				TemplateVariable variable = TemplateVariable.requestParameter(key);

				builder.queryParam(key, variable.prepareAndEncode(entry.getValue()));
			}

			return;
		}

		if (Map.class.isAssignableFrom(parameterType) && SKIP_VALUE.equals(value)) {
			return;
		}

		String key = parameter.getVariableName();
		TemplateVariable variable = TemplateVariable.requestParameter(key);

		if (value instanceof Collection) {

			if (parameter.isNonComposite()) {
				builder.queryParam(key, variable.prepareAndEncode(value));

			} else {
				for (Object element : (Collection<?>) value) {
					if (key != null) {
						builder.queryParam(key, variable.prepareAndEncode(element));
					}
				}
			}
		} else if (SKIP_VALUE.equals(value)) {

			if (parameter.isRequired()) {
				if (key != null) {
					builder.queryParam(key, String.format("{%s}", key));
				}
			}

		} else {
			if (key != null) {
				builder.queryParam(key, variable.prepareAndEncode(parameter.getValueAsString(arguments, conversionService)));
			}
		}
	}

	private static class HandlerMethodParameters {

		private static final List<Class<? extends Annotation>> ANNOTATIONS = Arrays.asList(RequestParam.class,
				PathVariable.class);
		private static final Map<Method, HandlerMethodParameters> CACHE = new ConcurrentHashMap<Method, HandlerMethodParameters>();

		private final MultiValueMap<Class<? extends Annotation>, HandlerMethodParameter> byAnnotationCache;

		private HandlerMethodParameters(MethodParameters parameters) {

			this.byAnnotationCache = new LinkedMultiValueMap<>();

			for (Class<? extends Annotation> annotation : ANNOTATIONS) {

				this.byAnnotationCache.putAll(parameters.getParametersWith(annotation).stream() //
						.map(it -> HandlerMethodParameter.of(it, annotation)) //
						.collect(Collectors.groupingBy(HandlerMethodParameter::getAnnotationType, LinkedMultiValueMap::new,
								Collectors.toList())));
			}
		}

		public static HandlerMethodParameters of(Method method) {

			return CACHE.computeIfAbsent(method, it -> {

				MethodParameters parameters = MethodParameters.of(it);
				return new HandlerMethodParameters(parameters);
			});
		}

		public List<HandlerMethodParameter> getParameterAnnotatedWith(Class<? extends Annotation> annotation,
				Object[] arguments) {

			List<HandlerMethodParameter> parameters = byAnnotationCache.get(annotation);

			if (parameters == null) {
				return Collections.emptyList();
			}

			List<HandlerMethodParameter> result = new ArrayList<>();

			for (HandlerMethodParameter parameter : parameters) {
				if (parameter.getVerifiedValue(arguments) != null) {
					result.add(parameter);
				}
			}

			return result;
		}
	}

	private abstract static class HandlerMethodParameter {

		private static final TypeDescriptor STRING_DESCRIPTOR = TypeDescriptor.valueOf(String.class);
		private static final Map<Class<? extends Annotation>, Function<MethodParameter, ? extends HandlerMethodParameter>> FACTORY;
		private static final String NO_PARAMETER_NAME = "Could not determine name of parameter %s! Make sure you compile with parameter information or explicitly define a parameter name in %s.";

		static {
			FACTORY = new HashMap<>();
			FACTORY.put(RequestParam.class, RequestParamParameter::new);
			FACTORY.put(PathVariable.class, PathVariableParameter::new);
		}

		private final MethodParameter parameter;
		private final AnnotationAttribute attribute;
		private final TypeDescriptor typeDescriptor;
		private final boolean isNonComposite;

		private String variableName;

		/**
		 * Creates a new {@link HandlerMethodParameter} for the given {@link MethodParameter} and
		 * {@link AnnotationAttribute}.
		 *
		 * @param parameter
		 * @param attribute
		 */
		private HandlerMethodParameter(MethodParameter parameter, AnnotationAttribute attribute) {

			this.parameter = parameter;
			this.attribute = attribute;

			int nestingIndex = Optional.class.isAssignableFrom(parameter.getParameterType()) ? 1 : 0;

			this.typeDescriptor = TypeDescriptor.nested(parameter, nestingIndex);
			this.isNonComposite = parameter.hasParameterAnnotation(NonComposite.class);

			if (isNonComposite) {

				Assert.isTrue(parameter.hasParameterAnnotation(RequestParam.class),
						"@NonComposite can only be used in combination with @RequestParam!");

				Class<?> parameterType = parameter.getParameterType();

				Assert.isTrue(parameterType.isArray() || Collection.class.isAssignableFrom(parameterType),
						"@NonComposite can only be used with collections or arrays!");
			}
		}

		/**
		 * Creates a new {@link HandlerMethodParameter} for the given {@link MethodParameter} and annotation type.
		 *
		 * @param parameter must not be {@literal null}.
		 * @param type must not be {@literal null}.
		 * @return
		 */
		public static HandlerMethodParameter of(MethodParameter parameter, Class<? extends Annotation> type) {

			Function<MethodParameter, ? extends HandlerMethodParameter> function = FACTORY.get(type);

			if (function == null) {
				throw new IllegalArgumentException(String.format("Unsupported annotation type %s!", type.getName()));
			}

			return function.apply(parameter);
		}

		Class<? extends Annotation> getAnnotationType() {
			return attribute.getAnnotationType();
		}

		/**
		 * Returns whether the
		 *
		 * @return
		 */
		boolean isNonComposite() {
			return isNonComposite;
		}

		public String getVariableName() {

			if (variableName == null) {
				this.variableName = determineVariableName();
			}

			return variableName;
		}

		public String getValueAsString(Object[] values, ConversionService conversionService) {

			Object value = values[parameter.getParameterIndex()];

			if (value == null) {
				throw new IllegalArgumentException("Cannot turn null value into required String!");
			}

			if (String.class.isInstance(value)) {
				return (String) value;
			}

			value = ObjectUtils.unwrapOptional(value);

			Object result = conversionService.canConvert(typeDescriptor, STRING_DESCRIPTOR)
					? conversionService.convert(value, typeDescriptor, STRING_DESCRIPTOR)
					: value == null ? null : value.toString();

			if (result == null) {
				throw new IllegalArgumentException(String.format("Conversion of value %s resulted in null!", value));
			}

			return (String) result;
		}

		private String determineVariableName() {

			if (attribute == null) {

				this.variableName = parameter.getParameterName();

				return variableName;
			}

			Annotation annotation = parameter.getParameterAnnotation(attribute.getAnnotationType());
			String parameterName = annotation != null ? attribute.getValueFrom(annotation) : "";

			if (parameterName != null && StringUtils.hasText(parameterName)) {
				return parameterName;
			}

			parameterName = parameter.getParameterName();

			if (parameterName == null) {
				throw new IllegalStateException(String.format(NO_PARAMETER_NAME, parameter, attribute.getAnnotationType()));
			}

			return parameterName;
		}

		/**
		 * Returns the value for the underlying {@link MethodParameter} potentially applying validation.
		 *
		 * @param values must not be {@literal null}.
		 * @return
		 */
		@Nullable
		public Object getVerifiedValue(Object[] values) {
			return values[parameter.getParameterIndex()];
		}

		public abstract boolean isRequired();
	}

	/**
	 * {@link HandlerMethodParameter} implementation to work with {@link RequestParam}.
	 *
	 * @author Oliver Drotbohm
	 */
	private static class RequestParamParameter extends HandlerMethodParameter {

		private final MethodParameter parameter;

		public RequestParamParameter(MethodParameter parameter) {

			super(parameter, new AnnotationAttribute(RequestParam.class));

			this.parameter = parameter;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.hateoas.server.core.WebHandler.HandlerMethodParameter#isRequired()
		 */
		@Override
		public boolean isRequired() {

			RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);

			if (parameter.isOptional()) {
				return false;
			}

			return annotation != null && annotation.required() //
					&& annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.hateoas.server.core.WebHandler.HandlerMethodParameter#verifyValue(java.lang.Object[])
		 */
		@Override
		@Nullable
		public Object getVerifiedValue(Object[] values) {

			Object value = ObjectUtils.unwrapOptional(values[parameter.getParameterIndex()]);

			if (value != null) {
				return value;
			}

			if (!isRequired() || parameter.isOptional()) {
				return SKIP_VALUE;
			}

			RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);

			return annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE) ? SKIP_VALUE : null;
		}
	}

	/**
	 * {@link HandlerMethodParameter} extension dealing with {@link PathVariable} parameters.
	 *
	 * @author Oliver Drotbohm
	 */
	private static class PathVariableParameter extends HandlerMethodParameter {

		public PathVariableParameter(MethodParameter parameter) {
			super(parameter, new AnnotationAttribute(PathVariable.class));
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.hateoas.server.core.WebHandler.HandlerMethodParameter#isRequired()
		 */
		@Override
		public boolean isRequired() {
			return true;
		}
	}
}
