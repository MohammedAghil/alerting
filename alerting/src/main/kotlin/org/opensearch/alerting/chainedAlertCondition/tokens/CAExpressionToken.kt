/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.chainedAlertCondition.tokens

/**
 * To define the tokens in Trigger expression such as query[tag=“sev1"] or query[name=“sev1"] or query[id=“sev1"]
 */
internal data class CAExpressionToken(val value: String) : ExpressionToken
