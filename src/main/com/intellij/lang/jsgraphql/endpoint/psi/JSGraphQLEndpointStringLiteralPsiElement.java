/**
 * Copyright (c) 2015-present, Jim Kynde Meyer
 * All rights reserved.
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.intellij.lang.jsgraphql.endpoint.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiLiteral;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry;

/**
 * A string literal which supports reference providers
 */
public class JSGraphQLEndpointStringLiteralPsiElement extends JSGraphQLEndpointPsiElement implements PsiLiteral {

	public JSGraphQLEndpointStringLiteralPsiElement(@NotNull ASTNode node) {
		super(node);
	}

	@Nullable
	@Override
	public Object getValue() {
		return getText();
	}

	@Override
	@NotNull
	public PsiReference[] getReferences() {
		return ReferenceProvidersRegistry.getReferencesFromProviders(this);
	}

	@Override
	public PsiReference getReference() {
		PsiReference[] references = getReferences();
		return references.length == 0 ? null : references[0];
	}


}
