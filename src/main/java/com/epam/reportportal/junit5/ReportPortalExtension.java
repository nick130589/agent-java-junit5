/*
 * Copyright 2019 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.reportportal.junit5;

import com.epam.reportportal.annotations.TestCaseId;
import com.epam.reportportal.annotations.attribute.Attributes;
import com.epam.reportportal.aspect.StepAspect;
import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.item.TestCaseIdEntry;
import com.epam.reportportal.service.tree.TestItemTree;
import com.epam.reportportal.utils.AttributeParser;
import com.epam.reportportal.utils.TestCaseIdUtils;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.OperationCompletionRS;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import io.reactivex.Maybe;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.*;
import rp.com.google.common.collect.Sets;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.epam.reportportal.junit5.ItemType.*;
import static com.epam.reportportal.junit5.Status.*;
import static com.epam.reportportal.junit5.SystemAttributesFetcher.collectSystemAttributes;
import static com.epam.reportportal.junit5.utils.ItemTreeUtils.createItemTreeKey;
import static com.epam.reportportal.service.tree.TestItemTree.createTestItemLeaf;
import static java.util.Optional.ofNullable;
import static rp.com.google.common.base.Throwables.getStackTraceAsString;

/*
 * ReportPortal Extension sends the results of test execution to ReportPortal in RealTime
 */
public class ReportPortalExtension
		implements Extension, BeforeAllCallback, BeforeEachCallback, AfterTestExecutionCallback, AfterEachCallback, AfterAllCallback,
				   TestWatcher, InvocationInterceptor {

	public static final TestItemTree TEST_ITEM_TREE = new TestItemTree();
	public static ReportPortal REPORT_PORTAL = ReportPortal.builder().build();

	private static final String TEST_TEMPLATE_EXTENSION_CONTEXT = "org.junit.jupiter.engine.descriptor.TestTemplateExtensionContext";
	private static final Map<String, Launch> launchMap = new ConcurrentHashMap<>();
	private final Map<String, Maybe<String>> idMapping = new ConcurrentHashMap<>();
	private final Map<String, Maybe<String>> testTemplates = new ConcurrentHashMap<>();
	private ThreadLocal<Boolean> isDisabledTest = new ThreadLocal<>();
	private final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(this);

	ReportPortal getReporter() {
		return REPORT_PORTAL;
	}

	String getLaunchId(ExtensionContext context) {
		return context.getRoot().getUniqueId();
	}

	Launch getLaunch(ExtensionContext context) {
		return launchMap.computeIfAbsent(getLaunchId(context), id -> {
			ReportPortal rp = getReporter();
			ListenerParameters params = rp.getParameters();
			StartLaunchRQ rq = new StartLaunchRQ();
			rq.setMode(params.getLaunchRunningMode());
			rq.setDescription(params.getDescription());
			rq.setName(params.getLaunchName());
			Set<ItemAttributesRQ> attributes = Sets.newHashSet(params.getAttributes());
			attributes.addAll(collectSystemAttributes(params.getSkippedAnIssue()));
			rq.setAttributes(attributes);
			rq.setStartTime(Calendar.getInstance().getTime());
			rq.setRerun(params.isRerun());
			rq.setRerunOf(StringUtils.isEmpty(params.getRerunOf()) ? null : params.getRerunOf());

			Launch launch = rp.newLaunch(rq);
			StepAspect.addLaunch(id, launch);
			Runtime.getRuntime().addShutdownHook(getShutdownHook(launch));
			Maybe<String> launchIdResponse = launch.start();
			if (params.isCallbackReportingEnabled()) {
				TEST_ITEM_TREE.setLaunchId(launchIdResponse);
			}
			return launch;
		});
	}

	@Override
	public void interceptBeforeAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
			ExtensionContext extensionContext) throws Throwable {
		String parentId = extensionContext.getUniqueId();
		String uniqueId = startBeforeAfter(invocationContext.getExecutable(), extensionContext, parentId, BEFORE_CLASS);
		finishBeforeAfter(invocation, extensionContext, uniqueId);
	}

	@Override
	public void interceptBeforeEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
			ExtensionContext extensionContext) throws Throwable {
		String parentId = extensionContext.getParent().get().getUniqueId();
		String uniqueId = startBeforeAfter(invocationContext.getExecutable(), extensionContext, parentId, BEFORE_METHOD);
		finishBeforeAfter(invocation, extensionContext, uniqueId);
	}

	@Override
	public void interceptAfterAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
			ExtensionContext extensionContext) throws Throwable {
		String parentId = extensionContext.getUniqueId();
		String uniqueId = startBeforeAfter(invocationContext.getExecutable(), extensionContext, parentId, AFTER_CLASS);
		finishBeforeAfter(invocation, extensionContext, uniqueId);
	}

	@Override
	public void interceptAfterEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
			ExtensionContext extensionContext) throws Throwable {
		String parentId = extensionContext.getParent().get().getUniqueId();
		String uniqueId = startBeforeAfter(invocationContext.getExecutable(), extensionContext, parentId, AFTER_METHOD);
		finishBeforeAfter(invocation, extensionContext, uniqueId);
	}

	@Override
	public void beforeAll(ExtensionContext context) {
		isDisabledTest.set(false);
		startTestItem(context, SUITE);
	}

	@Override
	public void beforeEach(ExtensionContext context) {
		isDisabledTest.set(false);
		startTemplate(context);
	}

	@Override
	public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
			ExtensionContext extensionContext) throws Throwable {
		startTestItem(extensionContext, invocationContext.getArguments(), STEP);
		invocation.proceed();
	}

	@Override
	public <T> T interceptTestFactoryMethod(Invocation<T> invocation, ReflectiveInvocationContext<Method> invocationContext,
			ExtensionContext extensionContext) throws Throwable {
		startTestItem(extensionContext, invocationContext.getArguments(), STEP);
		return invocation.proceed();
	}

	@Override
	public void interceptDynamicTest(Invocation<Void> invocation, ExtensionContext extensionContext) throws Throwable {
		startTestItem(extensionContext, STEP);
		try {
			invocation.proceed();
			finishTestItem(extensionContext);
		} catch (Throwable throwable) {
			sendStackTraceToRP(throwable);
			finishTestItem(extensionContext, FAILED);
			throw throwable;
		}
	}

	@Override
	public void interceptTestTemplateMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
			ExtensionContext extensionContext) throws Throwable {
		startTestItem(extensionContext, invocationContext.getArguments(), STEP);
		invocation.proceed();
	}

	@Override
	public void afterTestExecution(ExtensionContext context) {
		Status status = getExecutionStatus(context);
		if (FAILED.equals(status)) {
			context.getParent().ifPresent(c -> c.getStore(NAMESPACE).put(FAILED, Boolean.TRUE));
		}
		finishTestItem(context, status);
	}

	@Override
	public void afterEach(ExtensionContext context) {
	}

	@Override
	public void afterAll(ExtensionContext context) {
		if (context.getStore(NAMESPACE).get(FAILED) == null) {
			finishTestTemplates(context);
			finishTestItem(context);
		} else {
			finishTestTemplates(context, FAILED);
			finishTestItem(context, FAILED);
			context.getParent().ifPresent(p -> p.getStore(NAMESPACE).put(FAILED, Boolean.TRUE));
		}
	}

	@Override
	public void testDisabled(ExtensionContext context, Optional<String> reason) {
		if (Boolean.parseBoolean(System.getProperty("reportDisabledTests"))) {
			isDisabledTest.set(true);
			String description = reason.orElse(context.getDisplayName());
			startTestItem(context, Collections.emptyList(), STEP, description);
			finishTestItem(context);
		}
	}

	@Override
	public void testSuccessful(ExtensionContext context) {
	}

	@Override
	public void testAborted(ExtensionContext context, Throwable throwable) {
	}

	@Override
	public void testFailed(ExtensionContext context, Throwable throwable) {
	}

	private String startBeforeAfter(Method method, ExtensionContext context, String parentId, ItemType itemType) {
		Launch launch = getLaunch(context);
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setName(method.getName() + "()");
		rq.setDescription(method.getName());
		String uniqueId = parentId + "/[method:" + method.getName() + "()]";
		rq.setUniqueId(uniqueId);
		ofNullable(context.getTags()).ifPresent(it -> rq.setAttributes(it.stream()
				.map(tag -> new ItemAttributesRQ(null, tag))
				.collect(Collectors.toSet())));
		rq.setType(itemType.name());
		rq.setRetry(false);
		String codeRef = method.getDeclaringClass().getCanonicalName() + "." + method.getName();
		rq.setCodeRef(codeRef);
		TestCaseIdEntry testCaseIdEntry = ofNullable(method.getAnnotation(TestCaseId.class)).map(TestCaseId::value)
				.map(value -> new TestCaseIdEntry(value, value.hashCode()))
				.orElseGet(() -> getTestCaseId(codeRef));
		rq.setTestCaseId(testCaseIdEntry.getId());
		rq.setTestCaseHash(testCaseIdEntry.getHash());
		Maybe<String> itemId = launch.startTestItem(idMapping.get(parentId), rq);
		idMapping.put(uniqueId, itemId);
		StepAspect.setParentId(itemId);
		return uniqueId;
	}

	private void finishBeforeAfter(Invocation<Void> invocation, ExtensionContext context, String uniqueId) throws Throwable {
		try {
			invocation.proceed();
			finishBeforeAfter(context, uniqueId, PASSED);
		} catch (Throwable throwable) {
			sendStackTraceToRP(throwable);
			finishBeforeAfter(context, uniqueId, FAILED);
			throw throwable;
		}
	}

	private void finishBeforeAfter(ExtensionContext context, String uniqueId, Status status) {
		Launch launch = getLaunch(context);
		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setStatus(status.name());
		rq.setEndTime(Calendar.getInstance().getTime());
		launch.finishTestItem(idMapping.get(uniqueId), rq);
	}

	private void startTemplate(ExtensionContext context) {
		Optional<ExtensionContext> parent = context.getParent();
		if ((parent.isPresent() && TEST_TEMPLATE_EXTENSION_CONTEXT.equals(parent.get().getClass().getName()))) {
			if (!idMapping.containsKey(parent.get().getUniqueId())) {
				startTestItem(context.getParent().get(), TEMPLATE);
			}
		}
	}

	private void startTestItem(ExtensionContext context, List<Object> arguments, ItemType type) {
		startTestItem(context, arguments, type, null);
	}

	private void startTestItem(ExtensionContext context, ItemType type) {
		startTestItem(context, Collections.emptyList(), type, null);
	}

	private String getCodeRef(Method method) {
		return method.getDeclaringClass().getCanonicalName() + "." + method.getName();
	}

	private static String appendSuffixIfNotEmpty(String str, String suffix) {
		return str + (suffix.isEmpty() ? "" : "$" + suffix);
	}

	private String getCodeRef(ExtensionContext context, String currentCodeRef) {
		return context.getTestMethod().map(m -> appendSuffixIfNotEmpty(getCodeRef(m), currentCodeRef)).orElseGet(() -> {
			String newCodeRef = appendSuffixIfNotEmpty(context.getDisplayName(), currentCodeRef);
			return context.getParent().map(c -> getCodeRef(c, newCodeRef)).orElse(newCodeRef);
		});
	}

	private Method getTestMethod(ExtensionContext context) {
		return context.getTestMethod().orElseGet(() -> context.getParent().map(this::getTestMethod).orElse(null));
	}

	private static boolean isRetry(ExtensionContext context) {
		return context.getTestMethod().map(it -> Objects.nonNull(it.getAnnotation(RepeatedTest.class))).orElse(false);
	}

	private void startTestItem(ExtensionContext context, List<Object> arguments, ItemType type, String reason) {
		boolean isTemplate = false;
		if (TEMPLATE.equals(type)) {
			type = SUITE;
			isTemplate = true;
		}
		boolean retry = isRetry(context);

		TestItem testItem = getTestItem(context, retry);
		Launch launch = getLaunch(context);
		StartTestItemRQ rq = new StartTestItemRQ();

		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setName(testItem.getName());
		rq.setDescription(null != reason ? reason : testItem.getDescription());
		rq.setUniqueId(testItem.getUniqueId());
		rq.setType(type.name());
		rq.setRetry(retry);
		if (SUITE.equals(type)) {
			context.getTestClass().map(Class::getCanonicalName).ifPresent(codeRef -> {
				rq.setCodeRef(codeRef);
				TestCaseIdEntry testCaseIdEntry = getTestCaseId(codeRef);
				rq.setTestCaseId(testCaseIdEntry.getId());
				rq.setTestCaseHash(testCaseIdEntry.getHash());
			});
		} else {
			String codeRef = getCodeRef(context, "");
			rq.setCodeRef(codeRef);
			Method testMethod = getTestMethod(context);
			rq.setAttributes(getAttributes(testMethod));
			TestCaseIdEntry caseId = getTestCaseId(testMethod, codeRef, arguments);
			rq.setTestCaseId(caseId.getId());
			rq.setTestCaseHash(caseId.getHash());
		}
		ofNullable(testItem.getAttributes()).ifPresent(attributes -> ofNullable(rq.getAttributes()).orElseGet(() -> {
			rq.setAttributes(Sets.newHashSet());
			return rq.getAttributes();
		}).addAll(attributes));

		Maybe<String> itemId = context.getParent()
				.map(ExtensionContext::getUniqueId)
				.map(parentId -> Optional.ofNullable(idMapping.get(parentId)))
				.map(parentTest -> {
					Maybe<String> item = launch.startTestItem(parentTest.orElse(null), rq);
					if (getReporter().getParameters().isCallbackReportingEnabled()) {
						TEST_ITEM_TREE.getTestItems()
								.put(createItemTreeKey(testItem.getName()), createTestItemLeaf(parentTest.orElse(null), item, 0));
					}
					return item;
				})
				.orElseGet(() -> {
					Maybe<String> item = launch.startTestItem(rq);
					if (getReporter().getParameters().isCallbackReportingEnabled()) {
						TEST_ITEM_TREE.getTestItems().put(createItemTreeKey(testItem.getName()), createTestItemLeaf(item, 0));
					}
					return item;
				});
		if (isTemplate) {
			testTemplates.put(context.getUniqueId(), itemId);
		}
		idMapping.put(context.getUniqueId(), itemId);
		StepAspect.setParentId(itemId);
	}

	private Set<ItemAttributesRQ> getAttributes(Method method) {
		return ofNullable(method.getAnnotation(Attributes.class)).map(AttributeParser::retrieveAttributes).orElseGet(Sets::newHashSet);
	}

	private TestCaseIdEntry getTestCaseId(String codeRef) {
		return new TestCaseIdEntry(codeRef, codeRef.hashCode());
	}

	private TestCaseIdEntry getTestCaseId(Method method, String codeRef, List<Object> arguments) {
		if (method != null) {
			TestCaseId caseId = method.getAnnotation(TestCaseId.class);
			if (caseId != null) {
				return caseId.parametrized() ?
						TestCaseIdUtils.getParameterizedTestCaseId(method, arguments) :
						new TestCaseIdEntry(caseId.value(), caseId.value().hashCode());
			}
		}
		return new TestCaseIdEntry(StringUtils.join(codeRef, arguments), Arrays.deepHashCode(new Object[] { codeRef, arguments }));
	}

	private void finishTestTemplates(ExtensionContext context) {
		finishTestTemplates(context, isDisabledTest.get() ? SKIPPED : getExecutionStatus(context));
	}

	private void finishTestTemplates(ExtensionContext context, Status status) {
		getTestTemplateIds().forEach(id -> {
			Launch launch = getLaunch(context);
			FinishTestItemRQ rq = new FinishTestItemRQ();
			rq.setStatus(status.name());
			rq.setEndTime(Calendar.getInstance().getTime());
			launch.finishTestItem(idMapping.get(id), rq);
			testTemplates.entrySet().removeIf(e -> e.getKey().equals(id));
		});
	}

	private List<String> getTestTemplateIds() {
		List<String> keys = new ArrayList<>();
		for (Map.Entry<String, Maybe<String>> e : testTemplates.entrySet()) {
			if (e.getKey().contains("/[test-template:") && !e.getKey().contains("-invocation")) {
				keys.add(e.getKey());
			}
		}
		return keys;
	}

	private void finishTestItem(ExtensionContext context) {
		finishTestItem(context, isDisabledTest.get() ? SKIPPED : getExecutionStatus(context));
	}

	private void finishTestItem(ExtensionContext context, Status status) {
		Launch launch = getLaunch(context);
		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setStatus(status.name());
		rq.setEndTime(Calendar.getInstance().getTime());
		Maybe<OperationCompletionRS> finishResponse = launch.finishTestItem(idMapping.get(context.getUniqueId()), rq);
		if (getReporter().getParameters().isCallbackReportingEnabled()) {
			ofNullable(TEST_ITEM_TREE.getTestItems().get(createItemTreeKey(context))).ifPresent(itemLeaf -> itemLeaf.setFinishResponse(
					finishResponse));
		}
	}

	private static Status getExecutionStatus(ExtensionContext context) {
		Optional<Throwable> exception = context.getExecutionException();
		if (!exception.isPresent()) {
			return Status.PASSED;
		} else {
			sendStackTraceToRP(exception.get());
			return Status.FAILED;
		}
	}

	private static Thread getShutdownHook(final Launch launch) {
		return new Thread(() -> {
			FinishExecutionRQ rq = new FinishExecutionRQ();
			rq.setEndTime(Calendar.getInstance().getTime());
			launch.finish(rq);
		});
	}

	private static void sendStackTraceToRP(final Throwable cause) {
		ReportPortal.emitLog((java.util.function.Function<String, SaveLogRQ>) itemUuid -> {
			SaveLogRQ rq = new SaveLogRQ();
			rq.setItemUuid(itemUuid);
			rq.setLevel("ERROR");
			rq.setLogTime(Calendar.getInstance().getTime());
			if (cause != null) {
				rq.setMessage(getStackTraceAsString(cause));
			} else {
				rq.setMessage("Test has failed without exception");
			}
			rq.setLogTime(Calendar.getInstance().getTime());
			return rq;
		});
	}

	protected TestItem getTestItem(ExtensionContext context, boolean isRetry) {
		String name = isRetry ? context.getParent().get().getDisplayName() : context.getDisplayName();
		String uniqueId = isRetry ? context.getParent().get().getUniqueId() : context.getUniqueId();
		name = name.length() > 1024 ? name.substring(0, 1024) + "..." : name;
		String description = context.getDisplayName();
		Set<String> tags = context.getTags();
		return new TestItem(name, description, uniqueId, tags);
	}

	protected static class TestItem {

		private String name;
		private String description;
		private String uniqueId;
		private Set<ItemAttributesRQ> attributes;

		String getName() {
			return name;
		}

		String getDescription() {
			return description;
		}

		Set<ItemAttributesRQ> getAttributes() {
			return attributes;
		}

		public TestItem(String name, String description, String uniqueId, Set<String> tags) {
			this.name = name;
			this.description = description;
			this.uniqueId = uniqueId;
			this.attributes = tags.stream().map(it -> new ItemAttributesRQ(null, it)).collect(Collectors.toSet());
		}

		public String getUniqueId() {
			return uniqueId;
		}
	}
}
