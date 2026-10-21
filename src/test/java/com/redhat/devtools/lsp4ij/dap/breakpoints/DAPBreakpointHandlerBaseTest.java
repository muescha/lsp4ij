// SPDX-License-Identifier: EPL-2.0
package com.redhat.devtools.lsp4ij.dap.breakpoints;

import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.SuspendPolicy;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.impl.breakpoints.XBreakpointBase;
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Exercises the actual LSP4IJ request conversion with real IntelliJ breakpoints. */
public class DAPBreakpointHandlerBaseTest extends HeavyPlatformTestCase {
    public void testRemovalReplacesBreakpointsPerSource() throws Exception {
        var handler = handler();
        var requests = new ArrayList<SetBreakpointsArguments>();
        var server = server(requests);
        var dir = Files.createTempDirectory("dap-breakpoints-");
        var a = breakpoint(dir.resolve("a.lua"), 0);
        var b = breakpoint(dir.resolve("b.lua"), 0);
        var a2 = breakpoint(dir.resolve("a.lua"), 1);
        try {
            handler.registerBreakpoint(a);
            handler.registerBreakpoint(b);
            handler.registerBreakpoint(a2);
            handler.initialize(server, new Capabilities()).join();
            requests.clear();
            handler.unregisterBreakpoint(a2, false);
            assertLines(requests, dir.resolve("a.lua"), 1);
            requests.clear();
            // The final breakpoint in A must clear A even while B remains installed.
            handler.unregisterBreakpoint(a, false);
            assertLines(requests, dir.resolve("a.lua"));
            assertLines(requests, dir.resolve("b.lua"), 1);
            requests.clear();
            handler.unregisterBreakpoint(b, false);
            assertLines(requests, dir.resolve("b.lua"));
            requests.clear();
            handler.registerBreakpoint(a);
            assertLines(requests, dir.resolve("a.lua"), 1);
            requests.clear();
            // IntelliJ uses the same unregister path when muting/disabling breakpoints.
            handler.unregisterBreakpoint(a, true);
            assertLines(requests, dir.resolve("a.lua"));
        } finally {
            handler.dispose();
            com.intellij.openapi.util.io.FileUtil.delete(dir.toFile());
        }
    }

    public void testLogPointIsSentAsLogMessage() throws Exception {
        var dir = Files.createTempDirectory("dap-breakpoints-");
        try {
            var breakpoint = logPoint(breakpoint(dir.resolve("a.lua"), 0), "count");
            breakpoint.setCondition("count == 20");
            var sourceBreakpoint = DAPBreakpointHandlerBase.toSourceBreakpoint(1, breakpoint, logPointCapabilities());
            assertEquals("count == 20", sourceBreakpoint.getCondition());
            assertEquals("{count}", sourceBreakpoint.getLogMessage());
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(dir.toFile());
        }
    }

    public void testLogPointIsSentOnlyWhenTheAdapterCanLogWithoutSuspending() throws Exception {
        var dir = Files.createTempDirectory("dap-breakpoints-");
        try {
            var breakpoint = logPoint(breakpoint(dir.resolve("a.lua"), 0), "count");
            // The debug adapter doesn't support log points: IntelliJ evaluates the expression when it stops.
            assertNull(DAPBreakpointHandlerBase.toSourceBreakpoint(1, breakpoint, new Capabilities()).getLogMessage());

            // A DAP log point never stops, so a breakpoint which suspends is not a log point.
            breakpoint.setSuspendPolicy(SuspendPolicy.ALL);
            assertNull(DAPBreakpointHandlerBase.toSourceBreakpoint(1, breakpoint, logPointCapabilities()).getLogMessage());
            breakpoint.setSuspendPolicy(SuspendPolicy.NONE);

            // "Breakpoint hit" message and stack trace are printed by IntelliJ when the debug adapter stops.
            breakpoint.setLogMessage(true);
            assertNull(DAPBreakpointHandlerBase.toSourceBreakpoint(1, breakpoint, logPointCapabilities()).getLogMessage());
            breakpoint.setLogMessage(false);

            // Braces cannot be embedded in a DAP log message template.
            logPoint(breakpoint, "({a: count}).a");
            assertNull(DAPBreakpointHandlerBase.toSourceBreakpoint(1, breakpoint, logPointCapabilities()).getLogMessage());

            // "Evaluate and log" disabled
            ((XBreakpointBase<?, ?, ?>) breakpoint).setLogExpressionEnabled(false);
            assertNull(DAPBreakpointHandlerBase.toSourceBreakpoint(1, breakpoint, logPointCapabilities()).getLogMessage());
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(dir.toFile());
        }
    }

    public void testCapabilitiesUpdateSendsBreakpointsAgain() throws Exception {
        var handler = handler();
        var requests = new ArrayList<SetBreakpointsArguments>();
        var server = server(requests);
        var dir = Files.createTempDirectory("dap-breakpoints-");
        try {
            handler.registerBreakpoint(logPoint(breakpoint(dir.resolve("a.lua"), 0), "count"));
            // The 'initialize' response doesn't report log points...
            handler.initialize(server, new Capabilities()).join();
            assertEquals(1, requests.size());
            assertNull(requests.get(0).getBreakpoints()[0].getLogMessage());
            requests.clear();
            // ...the DAP 'capabilities' event reports them later.
            handler.updateCapabilities(server, logPointCapabilities()).join();
            assertEquals(1, requests.size());
            assertEquals("{count}", requests.get(0).getBreakpoints()[0].getLogMessage());
        } finally {
            handler.dispose();
            com.intellij.openapi.util.io.FileUtil.delete(dir.toFile());
        }
    }

    private static Capabilities logPointCapabilities() {
        var capabilities = new Capabilities();
        capabilities.setSupportsLogPoints(true);
        return capabilities;
    }

    private static XLineBreakpoint<DAPBreakpointProperties> logPoint(XLineBreakpoint<DAPBreakpointProperties> breakpoint, String expression) {
        breakpoint.setSuspendPolicy(SuspendPolicy.NONE);
        breakpoint.setLogExpressionObject(XExpressionImpl.fromText(expression));
        ((XBreakpointBase<?, ?, ?>) breakpoint).setLogExpressionEnabled(true);
        return breakpoint;
    }

    private DAPBreakpointHandlerBase<XLineBreakpoint<DAPBreakpointProperties>> handler() throws Exception {
        var type = Arrays.stream(com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensions())
            .filter(t -> t.getId().equals("DAPConfiguration")).findFirst().orElseThrow();
        var config = type.getConfigurationFactories()[0].createTemplateConfiguration(getProject());
        var environment = ExecutionEnvironmentBuilder.create(getProject(), DefaultDebugExecutor.getDebugExecutorInstance(), config).build();
        var descriptor = new com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor(new DAPRunConfigurationOptions(), environment, null) {
            @Override
            public com.intellij.execution.process.ProcessHandler startServer() {
                throw new UnsupportedOperationException();
            }
            @Override
            public String getId() {
                return "breakpoint-regression";
            }
            @Override
            public Map<String, Object> getDapParameters() {
                return Map.of();
            }
            @Override
            public com.intellij.openapi.fileTypes.FileType getFileType() {
                return com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE;
            }
            @Override
            public boolean isDebuggableFile(com.intellij.openapi.vfs.VirtualFile file, com.intellij.openapi.project.Project project) {
                return true;
            }
        };
        var session = (XDebugSession)Proxy.newProxyInstance(XDebugSession.class.getClassLoader(),new Class[]{XDebugSession.class}, (p, m, a) -> null);
        return new DAPBreakpointHandlerBase<XLineBreakpoint<DAPBreakpointProperties>>(DAPBreakpointType.class, session, descriptor, getProject()) {};
    }

    private static IDebugProtocolServer server(List<SetBreakpointsArguments> requests) {
        return (IDebugProtocolServer)Proxy.newProxyInstance(IDebugProtocolServer.class.getClassLoader(),new Class[]{IDebugProtocolServer.class}, (p, m, a) -> {
            if (m.getName().equals("setBreakpoints")) {
                requests.add((SetBreakpointsArguments) a[0]);
                var response = new SetBreakpointsResponse();
                response.setBreakpoints(new Breakpoint[0]);
                return CompletableFuture.completedFuture(response);
            }
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            throw new AssertionError(m.getName());
        });
    }
    private XLineBreakpoint<DAPBreakpointProperties> breakpoint(Path path, int line) throws Exception {
        Files.writeString(path, "local a = 1\nlocal b = 2\n");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        return WriteAction.compute(() -> XDebuggerManager.getInstance(getProject()).getBreakpointManager()
            .addLineBreakpoint(new DAPBreakpointType(), file.getUrl(), line, new DAPBreakpointProperties()));
    }
    private void assertLines(List<SetBreakpointsArguments> requests, Path path, int... lines) throws Exception {
        // IntelliJ may canonicalize Windows paths (including short directory names).
        // Match the source file rather than its platform-dependent path spelling.
        var matches = new ArrayList<SetBreakpointsArguments>();
        for (var request : requests) {
            if (Files.isSameFile(path, Path.of(request.getSource().getPath()))) {
                matches.add(request);
            }
        }
        assertEquals("Expected a replacement request for " + path, 1, matches.size());
        assertTrue(Arrays.equals(lines, Arrays.stream(matches.get(0).getBreakpoints()).mapToInt(SourceBreakpoint::getLine).toArray()));
    }
}
