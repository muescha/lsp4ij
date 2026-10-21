// SPDX-License-Identifier: EPL-2.0
package com.redhat.devtools.lsp4ij.dap.client;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ExceptionBreakpointsFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the merge of the DAP 'capabilities' event into the capabilities of the 'initialize' response. */
public class DAPClientCapabilitiesTest {

    @Test
    public void updateOverridesOnlyReportedCapabilities() {
        var capabilities = new Capabilities();
        capabilities.setSupportsConfigurationDoneRequest(true);
        capabilities.setSupportsTerminateRequest(true);

        var update = new Capabilities();
        update.setSupportsConditionalBreakpoints(true);
        update.setSupportsHitConditionalBreakpoints(true);
        update.setSupportsLogPoints(true);
        update.setSupportsTerminateRequest(false);
        var filter = new ExceptionBreakpointsFilter();
        filter.setFilter("uncaught");
        update.setExceptionBreakpointFilters(new ExceptionBreakpointsFilter[]{filter});

        DAPClient.mergeCapabilities(capabilities, update);

        assertEquals(Boolean.TRUE, capabilities.getSupportsLogPoints());
        assertEquals(Boolean.TRUE, capabilities.getSupportsConditionalBreakpoints());
        assertEquals(Boolean.TRUE, capabilities.getSupportsHitConditionalBreakpoints());
        assertEquals(Boolean.FALSE, capabilities.getSupportsTerminateRequest());
        assertArrayEquals(new ExceptionBreakpointsFilter[]{filter}, capabilities.getExceptionBreakpointFilters());
        // Not part of the update: unchanged
        assertEquals(Boolean.TRUE, capabilities.getSupportsConfigurationDoneRequest());
        assertNull(capabilities.getSupportsSetVariable());
    }
}
