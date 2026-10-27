// SPDX-License-Identifier: EPL-2.0
package com.redhat.devtools.lsp4ij.dap.breakpoints;

import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** The DAP results are mapped to the IntelliJ breakpoints they belong to, also when the debug adapter moves a breakpoint. */
public class DAPBreakpointMappingTest extends HeavyPlatformTestCase {

    private final List<String> presentations = new ArrayList<>();
    private Path dir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        dir = Files.createTempDirectory("dap-breakpoints-");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            com.intellij.openapi.util.io.FileUtil.delete(dir.toFile());
        } finally {
            super.tearDown();
        }
    }

    public void testMovedBreakpointIsVerified() throws Exception {
        var breakpoint = breakpoint("a.lua", 0);
        // The debug adapter moves the breakpoint from line 1 to line 2
        var handler = initialize(List.of(breakpoint), arguments -> response(moved(arguments.getBreakpoints()[0], 7, true, null)));
        try {
            assertEquals(List.of("verified:a.lua:0"), presentations);
            assertEquals(Integer.valueOf(7), breakpoint.getUserData(BreakpointHandlerBase.DAP_BREAKPOINT_ID));
        } finally {
            handler.dispose();
        }
    }

    public void testMovedBreakpointShowsTheMessageOfTheDebugAdapter() throws Exception {
        var breakpoint = breakpoint("a.lua", 0);
        var handler = initialize(List.of(breakpoint), arguments -> response(moved(arguments.getBreakpoints()[0], 7, false, "no code")));
        try {
            assertEquals(List.of("invalid:a.lua:0:no code"), presentations);
        } finally {
            handler.dispose();
        }
    }

    public void testTemporaryBreakpointResultIsNotMappedToAnIntelliJBreakpoint() throws Exception {
        var breakpoint = breakpoint("a.lua", 1);
        var handler = initialize(List.of(breakpoint), arguments -> {
            var results = new ArrayList<Breakpoint>();
            for (var sourceBreakpoint : arguments.getBreakpoints()) {
                // The IntelliJ breakpoint (line 2) is verified, the temporary breakpoint (line 1) is not
                results.add(moved(sourceBreakpoint, sourceBreakpoint.getLine() + 100, sourceBreakpoint.getLine() == 2, "temporary"));
            }
            return response(results.toArray(new Breakpoint[0]));
        });
        try {
            presentations.clear();
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve("a.lua"));
            handler.registerTemporaryBreakpoint(XDebuggerUtil.getInstance().createPosition(file, 0)).join();
            assertEquals(List.of("verified:a.lua:1"), presentations);
        } finally {
            handler.dispose();
        }
    }

    public void testStopIsMappedByHitBreakpointIds() throws Exception {
        var breakpoint = breakpoint("a.lua", 0);
        var handler = initialize(List.of(breakpoint), arguments -> response(moved(arguments.getBreakpoints()[0], 7, true, null)));
        try {
            // The debug adapter stops on the line where it moved the breakpoint
            var frame = new StackFrame();
            var source = new Source();
            source.setPath(dir.resolve("a.lua").toString());
            frame.setSource(source);
            frame.setLine(2);

            var stopped = new StoppedEventArguments();
            stopped.setReason(StoppedEventArgumentsReason.BREAKPOINT);
            stopped.setHitBreakpointIds(new Integer[]{7});
            assertSame(breakpoint, stopBreakpoint(handler, stopped, frame));

            // Without ids, the breakpoint is searched by the line where the debug adapter installed it...
            stopped.setHitBreakpointIds(null);
            assertSame(breakpoint, stopBreakpoint(handler, stopped, frame));
            // ...not by its IntelliJ line, which the debug adapter moved away from (ex: a step ends there)
            frame.setLine(1);
            assertNull(stopBreakpoint(handler, stopped, frame));
            frame.setLine(3);
            assertNull(stopBreakpoint(handler, stopped, frame));
        } finally {
            handler.dispose();
        }
    }

    public void testDebugpyMovesBreakpointsToThePreviousStatement() throws Exception {
        // Recorded with the debugpy bundled in IntelliJ IDEA 2026.3: breakpoints on a blank line (3) and on a
        // comment (4) are both moved to the statement on line 2, and the 'stopped' event has no hitBreakpointIds.
        var blankLine = breakpoint("a.lua", 2);
        var comment = breakpoint("a.lua", 3);
        var handler = initialize(List.of(blankLine, comment), arguments -> {
            var results = new ArrayList<Breakpoint>();
            for (int i = 0; i < arguments.getBreakpoints().length; i++) {
                var result = new Breakpoint();
                result.setId(i);
                result.setVerified(true);
                result.setLine(2);
                results.add(result);
            }
            return response(results.toArray(new Breakpoint[0]));
        });
        try {
            assertEquals(Set.of("verified:a.lua:2", "verified:a.lua:3"), new HashSet<>(presentations));

            var frame = new StackFrame();
            var source = new Source();
            source.setPath(dir.resolve("a.lua").toString());
            frame.setSource(source);
            frame.setLine(2);
            var stopped = new StoppedEventArguments();
            stopped.setReason(StoppedEventArgumentsReason.BREAKPOINT);
            assertNotNull(stopBreakpoint(handler, stopped, frame));
        } finally {
            handler.dispose();
        }
    }

    public void testStopPrefersTheBreakpointWhichWasNotMoved() throws Exception {
        // Line 2 has a breakpoint, and the debug adapter moves the breakpoint of the blank line 3 to line 2
        var moved = breakpoint("a.lua", 2);
        var notMoved = breakpoint("a.lua", 1);
        var handler = initialize(List.of(moved, notMoved), arguments -> {
            var results = new ArrayList<Breakpoint>();
            for (var sourceBreakpoint : arguments.getBreakpoints()) {
                var result = new Breakpoint();
                result.setId(sourceBreakpoint.getLine());
                result.setVerified(true);
                result.setLine(2);
                results.add(result);
            }
            return response(results.toArray(new Breakpoint[0]));
        });
        try {
            var frame = new StackFrame();
            var source = new Source();
            source.setPath(dir.resolve("a.lua").toString());
            frame.setSource(source);
            frame.setLine(2);
            assertSame(notMoved, stopBreakpoint(handler, new StoppedEventArguments(), frame));
        } finally {
            handler.dispose();
        }
    }

    public void testChangedEventUpdatesAllBreakpointsWithTheSameId() throws Exception {
        var a = breakpoint("a.lua", 0);
        var b = breakpoint("b.lua", 0);
        // The debug adapter returns the same id for both breakpoints
        var handler = initialize(List.of(a, b), arguments -> response(moved(arguments.getBreakpoints()[0], 7, true, null)));
        try {
            presentations.clear();
            var changed = new Breakpoint();
            changed.setId(7);
            changed.setVerified(false);
            changed.setMessage("removed");
            handler.updateBreakpoint(changed, null);
            assertEquals(Set.of("invalid:a.lua:0:removed", "invalid:b.lua:0:removed"), new HashSet<>(presentations));
        } finally {
            handler.dispose();
        }
    }

    private static Breakpoint moved(SourceBreakpoint sourceBreakpoint, int id, boolean verified, String message) {
        var breakpoint = new Breakpoint();
        breakpoint.setId(id);
        breakpoint.setLine(sourceBreakpoint.getLine() + 1);
        breakpoint.setVerified(verified);
        breakpoint.setMessage(message);
        return breakpoint;
    }

    private static SetBreakpointsResponse response(Breakpoint... breakpoints) {
        var response = new SetBreakpointsResponse();
        response.setBreakpoints(breakpoints);
        return response;
    }

    private DAPBreakpointHandlerBase<XLineBreakpoint<DAPBreakpointProperties>> initialize(List<XLineBreakpoint<DAPBreakpointProperties>> breakpoints,
                                                                                         Function<SetBreakpointsArguments, SetBreakpointsResponse> responses) throws Exception {
        var handler = handler();
        for (var breakpoint : breakpoints) {
            handler.registerBreakpoint(breakpoint);
        }
        var server = (IDebugProtocolServer) Proxy.newProxyInstance(IDebugProtocolServer.class.getClassLoader(), new Class[]{IDebugProtocolServer.class}, (p, m, a) -> {
            if (m.getName().equals("setBreakpoints")) {
                return CompletableFuture.completedFuture(responses.apply((SetBreakpointsArguments) a[0]));
            }
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            throw new AssertionError(m.getName());
        });
        handler.initialize(server, new Capabilities()).join();
        return handler;
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
                return "breakpoint-mapping";
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
        // Record how the breakpoints are presented: verified or invalid (with message)
        var session = (XDebugSession) Proxy.newProxyInstance(XDebugSession.class.getClassLoader(), new Class[]{XDebugSession.class}, (p, m, a) -> {
            if (m.getName().equals("setBreakpointVerified")) {
                presentations.add("verified:" + describe((XLineBreakpoint<?>) a[0]));
            } else if (m.getName().equals("setBreakpointInvalid")) {
                presentations.add("invalid:" + describe((XLineBreakpoint<?>) a[0]) + ":" + a[1]);
            }
            return null;
        });
        return new DAPBreakpointHandlerBase<>(DAPBreakpointType.class, session, descriptor, getProject()) {};
    }

    private static String describe(XLineBreakpoint<?> breakpoint) {
        return breakpoint.getSourcePosition().getFile().getName() + ":" + breakpoint.getLine();
    }

    private XLineBreakpoint<DAPBreakpointProperties> breakpoint(String fileName, int line) throws Exception {
        Path path = dir.resolve(fileName);
        Files.writeString(path, "local a = 1\nlocal b = 2\n\n-- comment\nlocal c = 3\n");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        return WriteAction.compute(() -> XDebuggerManager.getInstance(getProject()).getBreakpointManager()
                .addLineBreakpoint(new DAPBreakpointType(), file.getUrl(), line, new DAPBreakpointProperties()));
    }

    /**
     * Returns the breakpoint of a stop like DAPClient#getStopBreakpoint: by the hitBreakpointIds, and without ids
     * by the line of the top stack frame.
     */
    private static XBreakpoint<?> stopBreakpoint(DAPBreakpointHandlerBase<?> handler,
                                                 StoppedEventArguments stopped,
                                                 StackFrame frame) {
        Integer[] ids = stopped.getHitBreakpointIds();
        if (ids != null && ids.length > 0) {
            for (Integer id : ids) {
                var breakpoint = handler.findBreakpointById(id);
                if (breakpoint != null) {
                    return breakpoint;
                }
            }
            return null;
        }
        return handler.findBreakpoint(frame);
    }
}
