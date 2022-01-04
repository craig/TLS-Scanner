/**
 * TLS-Server-Scanner - A TLS configuration and analysis tool based on TLS-Attacker
 *
 * Copyright 2017-2022 Ruhr University Bochum, Paderborn University, Hackmanit GmbH
 *
 * Licensed under Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 */

package de.rub.nds.tlsscanner.serverscanner.guideline.results;

import de.rub.nds.tlsattacker.core.constants.HashAlgorithm;
import de.rub.nds.tlsscanner.serverscanner.guideline.GuidelineCheckResult;
import de.rub.nds.tlsscanner.serverscanner.rating.TestResult;

import java.util.Objects;

public class HashAlgorithmStrengthCheckResult extends GuidelineCheckResult {

    private final HashAlgorithm hashAlgorithm;

    public HashAlgorithmStrengthCheckResult(TestResult result, HashAlgorithm hashAlgorithm) {
        super(result);
        this.hashAlgorithm = hashAlgorithm;
    }

    @Override
    public String display() {
        if (Objects.equals(TestResult.TRUE, getResult())) {
            return "Used Hash Algorithms are strong enough.";
        }
        return hashAlgorithm + " is too weak";
    }
}
