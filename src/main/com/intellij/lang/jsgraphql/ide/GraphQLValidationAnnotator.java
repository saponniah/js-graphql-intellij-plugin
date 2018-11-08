/*
 * Copyright (c) 2018-present, Jim Kynde Meyer
 * All rights reserved.
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.intellij.lang.jsgraphql.ide;

import com.google.common.collect.Lists;
import com.intellij.codeInsight.daemon.impl.quickfix.RenameElementFix;
import com.intellij.codeInspection.*;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.AnnotationSession;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.jsgraphql.ide.project.GraphQLPsiSearchHelper;
import com.intellij.lang.jsgraphql.psi.GraphQLArgument;
import com.intellij.lang.jsgraphql.psi.*;
import com.intellij.lang.jsgraphql.psi.GraphQLDirective;
import com.intellij.lang.jsgraphql.psi.GraphQLDirectiveLocation;
import com.intellij.lang.jsgraphql.psi.GraphQLElementTypes;
import com.intellij.lang.jsgraphql.psi.GraphQLField;
import com.intellij.lang.jsgraphql.psi.GraphQLFieldDefinition;
import com.intellij.lang.jsgraphql.psi.GraphQLFragmentDefinition;
import com.intellij.lang.jsgraphql.psi.GraphQLFragmentSelection;
import com.intellij.lang.jsgraphql.psi.GraphQLFragmentSpread;
import com.intellij.lang.jsgraphql.psi.GraphQLIdentifier;
import com.intellij.lang.jsgraphql.psi.GraphQLObjectField;
import com.intellij.lang.jsgraphql.psi.GraphQLOperationDefinition;
import com.intellij.lang.jsgraphql.psi.GraphQLTypeCondition;
import com.intellij.lang.jsgraphql.psi.GraphQLTypeName;
import com.intellij.lang.jsgraphql.psi.GraphQLTypeSystemDefinition;
import com.intellij.lang.jsgraphql.schema.GraphQLSchemaWithErrors;
import com.intellij.lang.jsgraphql.schema.GraphQLTypeDefinitionRegistryServiceImpl;
import com.intellij.lang.jsgraphql.schema.GraphQLTypeScopeProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiEditorUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.text.EditDistance;
import graphql.AssertException;
import graphql.GraphQLError;
import graphql.language.Document;
import graphql.language.SourceLocation;
import graphql.parser.Parser;
import graphql.schema.*;
import graphql.schema.idl.errors.SchemaProblem;
import graphql.schema.validation.InvalidSchemaException;
import graphql.validation.ValidationError;
import graphql.validation.ValidationErrorType;
import graphql.validation.Validator;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class GraphQLValidationAnnotator implements Annotator {

    private static final Key<List<? extends GraphQLError>> ERRORS = Key.create(GraphQLValidationAnnotator.class.getName() + ".errors");
    private static final Key<Editor> EDITOR = Key.create(GraphQLValidationAnnotator.class.getName() + ".editor");

    @Override
    public void annotate(@NotNull PsiElement psiElement, @NotNull AnnotationHolder annotationHolder) {

        if (psiElement instanceof PsiWhiteSpace || psiElement instanceof PsiFile) {
            return;
        }

        // identifiers - fields, fragment spreads, field arguments, directives, type names, input object fields
        if (psiElement instanceof GraphQLIdentifier) {
            final PsiReference reference = psiElement.getReference();
            if (reference == null || reference.resolve() == null) {
                final PsiElement parent = psiElement.getParent();
                final GraphQLTypeScopeProvider typeScopeProvider = PsiTreeUtil.getParentOfType(parent, GraphQLTypeScopeProvider.class);
                graphql.schema.GraphQLType typeScope = null;
                if (typeScopeProvider != null) {
                    typeScope = typeScopeProvider.getTypeScope();
                    if (typeScope != null) {
                        // unwrap non-nulls and lists for type and field hints
                        typeScope = new SchemaUtil().getUnmodifiedType(typeScope);
                    }
                }

                String message = null;

                // fixes to automatically rename misspelled identifiers
                final List<LocalQuickFix> fixes = Lists.newArrayList();
                Consumer<List<String>> createFixes = (List<String> suggestions) -> {
                    suggestions.forEach(suggestion -> fixes.add(new RenameElementFix((PsiNamedElement) psiElement, suggestion)));
                };

                if (parent instanceof GraphQLField) {
                    message = "Unknown field \"" + psiElement.getText() + "\"";
                    if (typeScope != null) {
                        String definitionType = "";
                        if (typeScope instanceof GraphQLObjectType) {
                            definitionType = "object ";
                        } else if (typeScope instanceof GraphQLInterfaceType) {
                            definitionType = "interface ";
                        }
                        message += " on " + definitionType + "type \"" + typeScope.getName() + "\"";
                        final List<String> suggestions = getFieldNameSuggestions(psiElement.getText(), typeScope);
                        if (suggestions != null && !suggestions.isEmpty()) {
                            message += ". Did you mean " + formatSuggestions(suggestions) + "?";
                            createFixes.accept(suggestions);
                        }
                    } else {
                        // no type info available from the parent
                        message += ": The parent selection or operation does not resolve to a valid schema type";
                    }
                } else if (parent instanceof GraphQLFragmentSpread) {
                    message = "Unknown fragment spread \"" + psiElement.getText() + "\"";
                } else if (parent instanceof GraphQLArgument) {
                    message = "Unknown argument \"" + psiElement.getText() + "\"";
                    if (typeScope != null) {
                        final List<String> suggestions = getArgumentNameSuggestions(psiElement, typeScope);
                        if (!suggestions.isEmpty()) {
                            message += ". Did you mean " + formatSuggestions(suggestions) + "?";
                            createFixes.accept(suggestions);
                        }
                    }
                } else if (parent instanceof GraphQLDirective) {
                    message = "Unknown directive \"" + psiElement.getText() + "\"";
                } else if (parent instanceof GraphQLObjectField) {
                    message = "Unknown field \"" + psiElement.getText() + "\"";
                    if (typeScope != null) {
                        message += " on input type \"" + typeScope.getName() + "\"";
                        final List<String> suggestions = getFieldNameSuggestions(psiElement.getText(), typeScope);
                        if (suggestions != null && !suggestions.isEmpty()) {
                            message += ". Did you mean " + formatSuggestions(suggestions) + "?";
                            createFixes.accept(suggestions);
                        }
                    }
                } else if (parent instanceof GraphQLTypeName) {
                    message = "Unknown type \"" + psiElement.getText() + "\"";
                }
                if (message != null) {
                    final Optional<Annotation> annotation = createErrorAnnotation(annotationHolder, psiElement, message);
                    if(annotation.isPresent()) {
                        annotation.get().setTextAttributes(CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES);
                        if (!fixes.isEmpty()) {
                            final InspectionManager inspectionManager = InspectionManager.getInstance(psiElement.getProject());
                            final ProblemDescriptor problemDescriptor = inspectionManager.createProblemDescriptor(
                                    psiElement,
                                    psiElement,
                                    message,
                                    ProblemHighlightType.ERROR,
                                    true,
                                    LocalQuickFix.EMPTY_ARRAY
                            );
                            fixes.forEach(fix -> annotation.get().registerFix(fix, null, null, problemDescriptor));
                        }
                    }
                }
            }
        }

        // valid directive location names
        if (psiElement instanceof GraphQLDirectiveLocation) {
            final PsiReference reference = psiElement.getReference();
            if (reference == null || reference.resolve() == null) {
                Optional<Annotation> errorAnnotation = createErrorAnnotation(annotationHolder, psiElement, "Unknown directive location '" + psiElement.getText() + "'.");
                errorAnnotation.ifPresent(annotation -> annotation.setTextAttributes(CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES));
            }
        }

        // validation using graphql-java
        final AnnotationSession session = annotationHolder.getCurrentAnnotationSession();
        final PsiFile containingFile = psiElement.getContainingFile();
        final Project project = psiElement.getProject();

        List<? extends GraphQLError> userData = session.getUserData(ERRORS);
        if (userData == null) {

            // store the editor since we need to translate graphql-java source locations to psi elements
            Editor editor = session.getUserData(EDITOR);
            if (editor == null) {
                final FileEditor fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(containingFile.getVirtualFile());
                if (fileEditor instanceof TextEditor) {
                    editor = ((TextEditor) fileEditor).getEditor();
                    session.putUserData(EDITOR, editor);
                }
                if(editor == null) {
                    // no compatible editor found to annotate
                    return;
                }
            }

            final Parser parser = new Parser();
            try {
                final GraphQLSchemaWithErrors schema = GraphQLTypeDefinitionRegistryServiceImpl.getService(project).getSchemaWithErrors(psiElement);
                if (!schema.isErrorsPresent()) {
                    final Document document = parser.parseDocument(containingFile.getText());
                    userData = new Validator().validateDocument(schema.getSchema(), document);
                } else {

                    final List<String> errorMessages = Lists.newArrayList();
                    final String currentFileName = GraphQLPsiSearchHelper.getFileName(containingFile);
                    final Ref<SourceLocation> firstSchemaError = new Ref<>();
                    for (GraphQLError error : schema.getErrors()) {
                        errorMessages.add(error.getMessage() + formatLocation(error.getLocations()));
                        SourceLocation firstSourceLocation = error.getLocations().stream().findFirst().orElse(null);
                        if(firstSourceLocation != null && firstSchemaError.isNull()) {
                            firstSchemaError.set(firstSourceLocation);
                        }
                        if (firstSourceLocation != null && currentFileName.equals(firstSourceLocation.getSourceName())) {
                            final int positionToOffset = getOffsetFromSourceLocation(editor, firstSourceLocation);
                            PsiElement errorPsiElement = containingFile.findElementAt(positionToOffset);
                            if (errorPsiElement != null) {
                                PsiElement nextLeaf = PsiTreeUtil.nextVisibleLeaf(errorPsiElement);
                                if(nextLeaf != null && nextLeaf.getParent() instanceof GraphQLIdentifier) {
                                    // graphql-errors typically point to the keywords of definitions, so
                                    // use the definition identifier in that case
                                    errorPsiElement = nextLeaf.getParent();
                                }
                                createErrorAnnotation(annotationHolder, errorPsiElement, error.getMessage());
                            }
                        }
                    }

                    // schema errors are present, so mark operations and fragments with a message that type information is incomplete
                    final List<? extends GraphQLDefinition> operations = PsiTreeUtil.getChildrenOfAnyType(psiElement.getContainingFile(), GraphQLOperationDefinition.class, GraphQLFragmentDefinition.class);
                    final String fullErrorMessage = StringUtils.join(errorMessages, "\n");
                    for (GraphQLDefinition definition : operations) {
                        Optional<Annotation> errorAnnotation = createErrorAnnotation(annotationHolder, definition, "No type information available due to schema errors: \n" + fullErrorMessage);
                        if(!errorAnnotation.isPresent()) {
                            continue;
                        }
                        errorAnnotation.get().setTextAttributes(CodeInsightColors.WEAK_WARNING_ATTRIBUTES);

                        if(!firstSchemaError.isNull()) {
                            final InspectionManager inspectionManager = InspectionManager.getInstance(psiElement.getProject());
                            final ProblemDescriptor problemDescriptor = inspectionManager.createProblemDescriptor(
                                    definition,
                                    definition,
                                    "Navigate to GraphQL schema error",
                                    ProblemHighlightType.ERROR,
                                    true,
                                    LocalQuickFix.EMPTY_ARRAY
                            );

                            // help the user navigate to the schema error
                            errorAnnotation.get().registerFix(new LocalQuickFixOnPsiElement(definition) {

                                @NotNull
                                @Override
                                public String getText() {
                                    return "Navigate to GraphQL schema error";
                                }

                                @Nls
                                @NotNull
                                @Override
                                public String getFamilyName() {
                                    return "GraphQL schema errors";
                                }

                                @Override
                                public void invoke(@NotNull Project project, @NotNull PsiFile file, @NotNull PsiElement startElement, @NotNull PsiElement endElement) {
                                    final SourceLocation sourceLocation = firstSchemaError.get();
                                    final VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(sourceLocation.getSourceName());
                                    if(virtualFile != null) {
                                        final PsiFile schemaPsiFile = PsiManager.getInstance(project).findFile(virtualFile);
                                        if(schemaPsiFile != null) {
                                            schemaPsiFile.navigate(true);
                                            final Editor schemaEditor = PsiEditorUtil.Service.getInstance().findEditorByPsiElement(schemaPsiFile);
                                            if(schemaEditor != null) {
                                                final int positionToOffset = getOffsetFromSourceLocation(schemaEditor, sourceLocation);
                                                final NavigatablePsiElement navigatablePsiElement = PsiTreeUtil.getNonStrictParentOfType(
                                                        schemaPsiFile.findElementAt(positionToOffset),
                                                        NavigatablePsiElement.class
                                                );
                                                if(navigatablePsiElement != null) {
                                                    navigatablePsiElement.navigate(true);
                                                }
                                            }
                                        }
                                    }
                                }
                            }, null, null, problemDescriptor);
                        }

                    }

                    userData = Collections.emptyList();
                }
                session.putUserData(ERRORS, userData);
            } catch (SchemaProblem | CancellationException | InvalidSchemaException | AssertException e) {
                // error in graphql-java, so no validation available at this time
                session.putUserData(ERRORS, Collections.emptyList());
            }

            if (userData != null && editor != null) {
                for (GraphQLError userDatum : userData) {
                    if (userDatum instanceof ValidationError) {
                        final ValidationError validationError = (ValidationError) userDatum;
                        final ValidationErrorType validationErrorType = validationError.getValidationErrorType();
                        if (validationErrorType != null) {
                            switch (validationErrorType) {
                                case DefaultForNonNullArgument:
                                case WrongType:
                                case SubSelectionRequired:
                                case SubSelectionNotAllowed:
                                case BadValueForDefaultArg:
                                case InlineFragmentTypeConditionInvalid:
                                case FragmentTypeConditionInvalid:
                                case UnknownArgument:
                                case NonInputTypeOnVariable:
                                case MissingFieldArgument:
                                case MissingDirectiveArgument:
                                case VariableTypeMismatch:
                                case MisplacedDirective:
                                case UndefinedVariable:
                                case UnusedVariable:
                                case FragmentCycle:
                                case FieldsConflict:
                                case InvalidFragmentType:
                                case LoneAnonymousOperationViolation:
                                    for (SourceLocation location : validationError.getLocations()) {
                                        final int positionToOffset = getOffsetFromSourceLocation(editor, location);
                                        PsiElement errorPsiElement = containingFile.findElementAt(positionToOffset);
                                        if (errorPsiElement != null) {
                                            final IElementType elementType = errorPsiElement.getNode().getElementType();
                                            if (elementType == GraphQLElementTypes.SPREAD) {
                                                // graphql-java uses the '...' as source location on fragments, so find the fragment name or type condition
                                                final GraphQLFragmentSelection fragmentSelection = PsiTreeUtil.getParentOfType(errorPsiElement, GraphQLFragmentSelection.class);
                                                if (fragmentSelection != null) {
                                                    if (fragmentSelection.getFragmentSpread() != null) {
                                                        errorPsiElement = fragmentSelection.getFragmentSpread().getNameIdentifier();
                                                    } else if (fragmentSelection.getInlineFragment() != null) {
                                                        final GraphQLTypeCondition typeCondition = fragmentSelection.getInlineFragment().getTypeCondition();
                                                        if (typeCondition != null) {
                                                            errorPsiElement = typeCondition.getTypeName();
                                                        }
                                                    }
                                                }
                                            } else if (elementType == GraphQLElementTypes.AT) {
                                                // mark the directive and not only the '@'
                                                if(validationErrorType == ValidationErrorType.MisplacedDirective) {
                                                    // graphql-java KnownDirectives rule only recognizes executable directive locations, so ignore
                                                    // the error if we're inside a type definition
                                                    if(PsiTreeUtil.getTopmostParentOfType(errorPsiElement, GraphQLTypeSystemDefinition.class) != null) {
                                                        continue;
                                                    }
                                                }
                                                errorPsiElement = errorPsiElement.getParent();
                                            }
                                            if (errorPsiElement != null) {
                                                final String message = Optional.ofNullable(validationError.getDescription()).orElse(validationError.getMessage());
                                                createErrorAnnotation(annotationHolder, errorPsiElement, message);
                                            }
                                        }
                                    }
                                    break;
                                default:
                                    // remaining rules are handled above using psi references
                                    break;
                            }
                        }
                    }
                }
            }

        }
    }

    private Optional<Annotation> createErrorAnnotation(@NotNull AnnotationHolder annotationHolder, PsiElement errorPsiElement, String message) {
        if (GraphQLRelayModernAnnotationFilter.getService(errorPsiElement.getProject()).errorIsIgnored(errorPsiElement)) {
            return Optional.empty();
        }
        return Optional.of(annotationHolder.createErrorAnnotation(errorPsiElement, message));
    }

    private int getOffsetFromSourceLocation(Editor editor, SourceLocation location) {
        return editor.logicalPositionToOffset(new LogicalPosition(location.getLine() - 1, location.getColumn() - 1));
    }

    private String formatLocation(List<SourceLocation> locations) {
        if (!locations.isEmpty()) {
            final SourceLocation sourceLocation = locations.get(0);
            return ": " + sourceLocation.getSourceName() + ":" + sourceLocation.getLine() + ":" + sourceLocation.getColumn();
        }
        return "";
    }

    private List<String> getArgumentNameSuggestions(PsiElement argument, graphql.schema.GraphQLType typeScope) {
        final GraphQLField field = PsiTreeUtil.getParentOfType(argument, GraphQLField.class);
        final GraphQLIdentifier fieldDefinitionIdentifier = GraphQLPsiSearchHelper.getResolvedReference(field);
        if (fieldDefinitionIdentifier != null) {
            GraphQLFieldDefinition fieldDefinition = PsiTreeUtil.getParentOfType(fieldDefinitionIdentifier, GraphQLFieldDefinition.class);
            if (fieldDefinition != null) {
                final GraphQLArgumentsDefinition argumentsDefinition = fieldDefinition.getArgumentsDefinition();
                if (argumentsDefinition != null) {
                    final List<String> argumentNames = Lists.newArrayList();
                    argumentsDefinition.getInputValueDefinitionList().forEach(arg -> {
                        if (arg.getName() != null) {
                            argumentNames.add(arg.getName());
                        }
                    });
                    return getSuggestions(argument.getText(), argumentNames);
                }
            }
        }
        return Collections.emptyList();
    }

    private List<String> getFieldNameSuggestions(String fieldName, graphql.schema.GraphQLType typeScope) {
        List<String> fieldNames = null;
        if (typeScope instanceof GraphQLFieldsContainer) {
            fieldNames = ((GraphQLFieldsContainer) typeScope).getFieldDefinitions().stream().map(graphql.schema.GraphQLFieldDefinition::getName).collect(Collectors.toList());
        } else if (typeScope instanceof GraphQLInputFieldsContainer) {
            fieldNames = ((GraphQLInputFieldsContainer) typeScope).getFieldDefinitions().stream().map(GraphQLInputObjectField::getName).collect(Collectors.toList());
        }
        if (fieldNames != null) {
            return getSuggestions(fieldName, fieldNames);
        }
        return Collections.emptyList();
    }

    @NotNull
    private List<String> getSuggestions(String text, List<String> candidates) {
        return candidates.stream()
                .map(suggestion -> new Pair<>(suggestion, EditDistance.optimalAlignment(text, suggestion, false)))
                .filter(p -> p.second <= 2)
                .sorted(Comparator.comparingInt(p -> p.second))
                .map(p -> p.first).collect(Collectors.toList());

    }

    private String formatSuggestions(List<String> suggestions) {
        if (suggestions != null && !suggestions.isEmpty()) {
            return "\"" + StringUtils.join(suggestions, "\", or \"") + "\"";
        }
        return null;
    }

}
