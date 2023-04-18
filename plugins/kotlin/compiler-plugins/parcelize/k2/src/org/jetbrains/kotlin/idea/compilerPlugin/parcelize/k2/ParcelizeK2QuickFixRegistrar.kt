// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.compilerPlugin.parcelize.k2

import com.intellij.codeInsight.intention.IntentionAction
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.diagnostics.KtDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KtCompilerPluginDiagnostic0
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KtCompilerPluginDiagnostic1
import org.jetbrains.kotlin.analysis.api.symbols.KtClassOrObjectSymbol
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixRegistrar
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KtQuickFixesListBuilder
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.diagnosticFixFactoryFromIntentionActions
import org.jetbrains.kotlin.idea.codeinsight.api.classic.quickfixes.QuickFixesPsiBasedFactory
import org.jetbrains.kotlin.idea.compilerPlugin.parcelize.quickfixes.*
import org.jetbrains.kotlin.idea.quickfix.RemoveModifierFixBase
import org.jetbrains.kotlin.parcelize.fir.diagnostics.KtErrorsParcelize
import org.jetbrains.kotlin.psi.KtClassOrObject

class ParcelizeK2QuickFixRegistrar : KotlinQuickFixRegistrar() {
    override val list = KtQuickFixesListBuilder.registerPsiQuickFix {
        registerFirQuickFix(KtErrorsParcelize.PARCELABLE_CANT_BE_INNER_CLASS, RemoveModifierFixBase.removeInnerModifier)

        registerFirQuickFix(KtErrorsParcelize.NO_PARCELABLE_SUPERTYPE, ParcelizeAddSupertypeQuickFix.FACTORY)
        registerFirQuickFix(KtErrorsParcelize.PARCELABLE_SHOULD_HAVE_PRIMARY_CONSTRUCTOR, ParcelizeAddPrimaryConstructorQuickFix.FACTORY)
        registerFirQuickFix(KtErrorsParcelize.PROPERTY_WONT_BE_SERIALIZED, ParcelizeAddIgnoreOnParcelAnnotationQuickFix.FACTORY)
        registerFirQuickFix(KtErrorsParcelize.REDUNDANT_TYPE_PARCELER, ParcelizeRemoveDuplicatingTypeParcelerAnnotationQuickFix.FACTORY)

        registerFirQuickFix(KtErrorsParcelize.OVERRIDING_WRITE_TO_PARCEL_IS_NOT_ALLOWED, ParcelRemoveCustomWriteToParcel.FACTORY)

        registerFirQuickFix(KtErrorsParcelize.CREATOR_DEFINITION_IS_NOT_ALLOWED, ParcelRemoveCustomCreatorProperty.FACTORY)

        registerApplicator(
            createApplicatorForFactory<KtCompilerPluginDiagnostic1>(
                { it.firDiagnostic.factory == KtErrorsParcelize.CLASS_SHOULD_BE_PARCELIZE },
                { diagnostic ->
                    listOfNotNull(
                        (diagnostic.parameter1 as? KtClassOrObjectSymbol)?.let { symbol ->
                            AnnotateWithParcelizeQuickFix(symbol.psi as KtClassOrObject)
                        }
                    )
                }
            )
        )
    }
}

private fun KtQuickFixesListBuilder.registerFirQuickFix(
    diagnosticFactory: KtDiagnosticFactory0,
    quickFixFactory: QuickFixesPsiBasedFactory<*>) {
    registerApplicator(
        createApplicatorForFactory<KtCompilerPluginDiagnostic0>(
            { it.firDiagnostic.factory == diagnosticFactory },
            { quickFixFactory.createQuickFix(it.psi) }
        )
    )
}

private fun KtQuickFixesListBuilder.registerFirQuickFix(
    diagnosticFactory: KtDiagnosticFactory1<*>,
    quickFixFactory: QuickFixesPsiBasedFactory<*>) {
    registerApplicator(
        createApplicatorForFactory<KtCompilerPluginDiagnostic1>(
            { it.firDiagnostic.factory == diagnosticFactory },
            { quickFixFactory.createQuickFix(it.psi) }
        )
    )
}

private inline fun <reified DIAGNOSTIC : KtDiagnosticWithPsi<*>> createApplicatorForFactory(
    crossinline matchesFactory: KtAnalysisSession.(DIAGNOSTIC) -> Boolean,
    crossinline createQuickFixes: KtAnalysisSession.(DIAGNOSTIC) -> List<IntentionAction>
) = diagnosticFixFactoryFromIntentionActions(DIAGNOSTIC::class) { diagnostic ->
    if (matchesFactory(diagnostic)) {
        createQuickFixes(diagnostic)
    } else {
        emptyList()
    }
}
