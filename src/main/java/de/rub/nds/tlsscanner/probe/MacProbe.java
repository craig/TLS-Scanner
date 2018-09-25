/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.rub.nds.tlsscanner.probe;

import de.rub.nds.modifiablevariable.VariableModification;
import de.rub.nds.modifiablevariable.bytearray.ByteArrayModificationFactory;
import de.rub.nds.modifiablevariable.bytearray.ModifiableByteArray;
import de.rub.nds.tlsattacker.attacks.util.response.EqualityError;
import de.rub.nds.tlsattacker.attacks.util.response.FingerPrintChecker;
import de.rub.nds.tlsattacker.attacks.util.response.ResponseExtractor;
import de.rub.nds.tlsattacker.attacks.util.response.ResponseFingerprint;
import de.rub.nds.tlsattacker.core.config.Config;
import de.rub.nds.tlsattacker.core.constants.AlgorithmResolver;
import de.rub.nds.tlsattacker.core.constants.CipherSuite;
import de.rub.nds.tlsattacker.core.constants.HandshakeMessageType;
import de.rub.nds.tlsattacker.core.constants.ProtocolVersion;
import de.rub.nds.tlsattacker.core.constants.RunningModeType;
import de.rub.nds.tlsattacker.core.record.Record;
import de.rub.nds.tlsattacker.core.state.State;
import de.rub.nds.tlsattacker.core.workflow.DefaultWorkflowExecutor;
import de.rub.nds.tlsattacker.core.workflow.WorkflowExecutor;
import de.rub.nds.tlsattacker.core.workflow.WorkflowTrace;
import de.rub.nds.tlsattacker.core.workflow.WorkflowTraceUtil;
import de.rub.nds.tlsattacker.core.workflow.action.GenericReceiveAction;
import de.rub.nds.tlsattacker.core.workflow.action.SendAction;
import de.rub.nds.tlsattacker.core.workflow.factory.WorkflowConfigurationFactory;
import de.rub.nds.tlsattacker.core.workflow.factory.WorkflowTraceType;
import de.rub.nds.tlsscanner.config.ScannerConfig;
import de.rub.nds.tlsscanner.constants.MacCheckPatternType;
import de.rub.nds.tlsscanner.constants.ProbeType;
import de.rub.nds.tlsscanner.report.SiteReport;
import de.rub.nds.tlsscanner.report.result.MacResult;
import de.rub.nds.tlsscanner.report.result.ProbeResult;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 *
 * @author robert
 */
public class MacProbe extends TlsProbe {

    private List<CipherSuite> suiteList;

    private Boolean didReceiveFinishedAndAlert = null;

    private ResponseFingerprint correctFingerprint;

    public MacProbe(ScannerConfig scannerConfig) {
        super(ProbeType.MAC, scannerConfig, 4);
    }

    @Override
    public ProbeResult executeTest() {
        correctFingerprint = getCorrectAppDataFingerprint();
        MacCheckPattern appPattern = getMacCheckPattern(Check.APPDATA);
        MacCheckPattern finishedPattern = getMacCheckPattern(Check.FINISHED);
        return new MacResult(appPattern, finishedPattern);
    }

    private ResponseFingerprint getCorrectAppDataFingerprint() {
        Config config = scannerConfig.createConfig();
        config.setAddRenegotiationInfoExtension(true);
        config.setQuickReceive(true);
        config.setDefaultClientSupportedCiphersuites(suiteList.get(0));
        config.setDefaultSelectedCipherSuite(suiteList.get(0));
        config.setAddServerNameIndicationExtension(true);
        config.setWorkflowExecutorShouldClose(false);
        config.setDefaultApplicationMessageData("GET / HTTP/1.0\n"
                + "Host: " + scannerConfig.getClientDelegate().getHost() + "\n"
                + "\n\n");

        WorkflowTrace trace = new WorkflowConfigurationFactory(config).createWorkflowTrace(WorkflowTraceType.FULL, RunningModeType.CLIENT);
        trace.addTlsAction(new GenericReceiveAction());

        State state = new State(config, trace);
        WorkflowExecutor executor = new DefaultWorkflowExecutor(state);
        executor.executeWorkflow();
        
        ResponseFingerprint fingerprint = ResponseExtractor.getFingerprint(state);
        try {
            state.getTlsContext().getTransportHandler().closeConnection();
        } catch (IOException ex) {
            LOGGER.warn("Could not close TransportHandler correctly", ex);
        }
        return fingerprint;
    }

    private WorkflowTrace getAppDataTrace(Config config, int xorPosition) {
        VariableModification<byte[]> xor = ByteArrayModificationFactory.xor(new byte[]{1}, xorPosition);
        WorkflowTrace trace = new WorkflowConfigurationFactory(config).createWorkflowTrace(WorkflowTraceType.FULL, RunningModeType.CLIENT);
        SendAction lastSendingAction = (SendAction) trace.getLastSendingAction();
        Record r = new Record();
        r.prepareComputations();
        ModifiableByteArray modMac = new ModifiableByteArray();
        r.getComputations().setMac(modMac);
        modMac.setModification(xor);
        lastSendingAction.setRecords(r);
        trace.addTlsAction(new GenericReceiveAction());
        return trace;
    }

    private WorkflowTrace getFinishedTrace(Config config, int xorPosition) {
        VariableModification<byte[]> xor = ByteArrayModificationFactory.xor(new byte[]{1}, xorPosition);
        WorkflowTrace trace = new WorkflowConfigurationFactory(config).createWorkflowTrace(WorkflowTraceType.HANDSHAKE, RunningModeType.CLIENT);
        SendAction lastSendingAction = (SendAction) trace.getLastSendingAction();
        Record r = new Record();
        r.prepareComputations();
        ModifiableByteArray modMac = new ModifiableByteArray();
        r.getComputations().setMac(modMac);
        modMac.setModification(xor);
        lastSendingAction.setRecords(new Record(), new Record(), r);
        return trace;
    }

    private MacCheckPattern getMacCheckPattern(Check check) {
        //We do not check all ciphersuite select one and test that one
        boolean[] macByteCheckMap = getMacByteCheckMap(check);
        boolean allTrue = true;
        boolean allFalse = true;
        for (int i = 0; i < macByteCheckMap.length; i++) {
            if (!macByteCheckMap[i]) {
                allTrue = false;
            }
        }
        for (int i = 0; i < macByteCheckMap.length; i++) {
            if (macByteCheckMap[i]) {
                allFalse = false;
            }
        }
        MacCheckPatternType type;
        if (allFalse) {
            type = MacCheckPatternType.NONE;
        } else if (allTrue) {
            type = MacCheckPatternType.CORRECT;
        } else {
            type = MacCheckPatternType.PARTIAL;
        }
        return new MacCheckPattern(type, false, macByteCheckMap);

    }

    private enum Check {
        FINISHED, APPDATA
    }

    private boolean[] getMacByteCheckMap(Check check) {
        CipherSuite suite = suiteList.get(0);
        //TODO Protocolversion not from report
        int macSize = AlgorithmResolver.getMacAlgorithm(ProtocolVersion.TLS12, suite).getSize();
        boolean[] byteCheckArray = new boolean[macSize];
        for (int i = 0; i < macSize; i++) {

            Config config = scannerConfig.createConfig();
            config.setAddRenegotiationInfoExtension(true);
            config.setQuickReceive(true);
            config.setDefaultClientSupportedCiphersuites(suite);
            config.setDefaultSelectedCipherSuite(suite);
            config.setAddServerNameIndicationExtension(true);
            config.setWorkflowExecutorShouldClose(false);
            config.setDefaultApplicationMessageData("GET / HTTP/1.0\n"
                    + "Host: " + scannerConfig.getClientDelegate().getHost() + "\n"
                    + "\n\n");

            WorkflowTrace trace;
            if (check == Check.APPDATA) {
                trace = getAppDataTrace(config, i);
            } else {
                trace = getFinishedTrace(config, i);
            }
            State state = new State(config, trace);
            WorkflowExecutor executor = new DefaultWorkflowExecutor(state);
            executor.executeWorkflow();
            if (check == Check.APPDATA) {
                ResponseFingerprint fingerprint = ResponseExtractor.getFingerprint(state);
                EqualityError equalityError = FingerPrintChecker.checkEquality(fingerprint, correctFingerprint, true);
                if (equalityError != EqualityError.NONE) {
                    byteCheckArray[i] = true;
                } else {
                    byteCheckArray[i] = false;
                }
            } else {
                if (WorkflowTraceUtil.didReceiveMessage(HandshakeMessageType.FINISHED, trace)) {
                    byteCheckArray[i] = false;
                } else {
                    byteCheckArray[i] = true;
                }
            }
            try {
                state.getTlsContext().getTransportHandler().closeConnection();
            } catch (IOException ex) {
                LOGGER.warn("Could not close TransportHandler");
            }
        }
        return byteCheckArray;
    }

    @Override
    public boolean shouldBeExecuted(SiteReport report) {
        List<CipherSuite> allSuiteList = new LinkedList<>();
        allSuiteList.addAll(report.getCipherSuites());
        if (allSuiteList != null) {
            for (CipherSuite suite : allSuiteList) {
                if (suite.isUsingMac()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void adjustConfig(SiteReport report) {
        List<CipherSuite> allSuiteList = new LinkedList<>();
        allSuiteList.addAll(report.getCipherSuites());
        suiteList = new LinkedList<>();
        if (allSuiteList != null) {
            for (CipherSuite suite : allSuiteList) {
                if (suite.isUsingMac()) {
                    suiteList.add(suite);
                }
            }
        }
    }

    @Override
    public ProbeResult getNotExecutedResult() {
        return new MacResult(new MacCheckPattern(MacCheckPatternType.UNKNOWN, false, null), new MacCheckPattern(MacCheckPatternType.UNKNOWN, false, null));
    }

}
