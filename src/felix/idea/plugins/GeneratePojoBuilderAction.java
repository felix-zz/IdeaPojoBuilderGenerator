package felix.idea.plugins;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.intellij.lang.Language;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by felix on 2017/11/14.
 *
 * @author felix
 */
public class GeneratePojoBuilderAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Language language = e.getData(LangDataKeys.LANGUAGE);
        if (!(language instanceof JavaLanguage)) {
            return;
        }
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        final Document document = editor.getDocument();
        String source = document.getText();
        final String sourceWithBuilder = insertBuilder(source);
        System.out.println(sourceWithBuilder);
        if (sourceWithBuilder == null) {
            return;
        }
        Runnable writer = () -> document.setText(sourceWithBuilder);
        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        WriteCommandAction.runWriteCommandAction(project, writer);
    }

    private static final Pattern BRACE_START = Pattern.compile("\\{\\s*}?(//.*)?(/\\*.*)?$");
    private static final Pattern BRACE_END = Pattern.compile("}\\s*(//.*)?(/\\*.*)?$");
    private static final Pattern FIELD_DECLARE =
            Pattern.compile("^\\s*((public|protected|private)\\s+)?(transient\\s+)?[\\w$]+(\\s*<.+>)?\\s+[\\w$]+\\s*;");
    private static final Pattern FIELD_TYPE_NAME =
            Pattern.compile("[\\w$]+(\\s*<.+>)?\\s+[\\w$]+\\s*;");
    private static final Pattern CLASS_DECLARE =
            Pattern.compile("^\\s*((public|protected|private)\\s+)?(static\\s+)?(final\\s+)?class\\s+[\\w$]+");
    private static final Pattern CLASS_NAME = Pattern.compile("[\\w$]+$");
    private static final Pattern BUILDER_METHOD =
            Pattern.compile("^\\s*((public|protected|private)\\s+)?(static\\s+)?Builder\\s+builder\\s*\\(\\)");

    private static final String GROUP_DISPLAY_ID = "POJO Builder Generator Notification";

    private static class FieldDeclaration {
        private final String name;
        private final String type;

        public FieldDeclaration(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }
    }

    private static void displayError(String message) {
        Notifications.Bus.notify(new Notification(
                GROUP_DISPLAY_ID,
                "POJO Builder Generation Failed",
                message == null ? "Unknown error occurred." : message,
                NotificationType.ERROR
        ));
    }

    private String insertBuilder(String source) {
        BufferedReader reader = new BufferedReader(new StringReader(source));
        String line;
        try {
            int braceDepth = 0;
            List<String> sourceLines = new LinkedList<>();
            List<FieldDeclaration> fields = new LinkedList<>();
            String thisClass = null;
            boolean autoGenBlock = false;
            while ((line = reader.readLine()) != null) {
                Matcher classMatcher = CLASS_DECLARE.matcher(line);
                if (classMatcher.find()) {
                    String classDeclare = classMatcher.group();
                    Matcher matcher = CLASS_NAME.matcher(classDeclare);
                    matcher.find();
                    String className = matcher.group();
                    if (braceDepth == 0) {
                        thisClass = className;
                    } else {
                        if ("Builder".equalsIgnoreCase(className)) {
                            autoGenBlock = true;
                        }
                    }
                }
                boolean depthIncreased = false;
                boolean depthDecreased = false;
                if (BRACE_START.matcher(line).find()) {
                    depthIncreased = true;
                    ++braceDepth;
                }
                if (BRACE_END.matcher(line).find()) {
                    if (!depthIncreased) {
                        depthDecreased = true;
                    }
                    --braceDepth;
                }
                if (braceDepth == 1 && thisClass == null) {
                    displayError("This is not a valid POJO class.");
                    return null;
                }
                if (BUILDER_METHOD.matcher(line).find()) {
                    autoGenBlock = true;
                }
                if (autoGenBlock) {
                    if (braceDepth == 1 && depthDecreased) {
                        autoGenBlock = false;
                    }
                    continue;
                }
                if (braceDepth == 1 && FIELD_DECLARE.matcher(line).find()) {
                    Matcher matcher = FIELD_TYPE_NAME.matcher(line);
                    matcher.find();
                    String typeAndName = matcher
                            .group()
                            .replaceAll("\\s*;$", "")
                            .replaceAll("\\s+", " ");
                    int fieldNamePreSpace = typeAndName.lastIndexOf(' ');
                    String name = typeAndName.substring(fieldNamePreSpace + 1);
                    String type = typeAndName.substring(0, fieldNamePreSpace);
                    fields.add(new FieldDeclaration(name, type));
                }
                if (braceDepth == 0 && !fields.isEmpty()) {
                    sourceLines.addAll(ImmutableList.of(
                            "",
                            "    public static class Builder {",
                            "        /**",
                            "         * Generated by POJO Builder Generate Plugin.",
                            "         */ ",
                            "",
                            String.format("        private final %s instance = new %s();\n", thisClass, thisClass)
                    ));
                    for (FieldDeclaration f : fields) {
                        sourceLines.addAll(ImmutableList.of(
                                String.format(
                                        "        public Builder %s(%s %s) {",
                                        f.getName(),
                                        f.getType(),
                                        f.getName()
                                ),
                                String.format(
                                        "            instance.set%s(%s);",
                                        StringUtils.capitalize(f.getName()),
                                        f.getName()
                                ),
                                "            return this;",
                                "        }\n"
                        ));
                    }
                    sourceLines.addAll(ImmutableList.of(
                            "        public " + thisClass + " build() {",
                            String.format("            %s copy = new %s();", thisClass, thisClass)
                    ));
                    for (FieldDeclaration f : fields) {
                        String cap = StringUtils.capitalize(f.getName());
                        sourceLines.add(
                                String.format("            copy.set%s(instance.get%s());", cap, cap)
                        );
                    }
                    sourceLines.addAll(ImmutableList.of(
                            "            return copy;",
                            "        }\n",
                            "    }\n",
                            "",
                            "    public static Builder builder() {",
                            "        /**",
                            "         * Generated by POJO Builder Generate Plugin.",
                            "         */ ",
                            "        return new Builder();",
                            "    }",
                            ""
                    ));
                }
                sourceLines.add(line);
            } // while loop
            return Joiner.on('\n').join(sourceLines);
        } catch (Exception e) {
            displayError("Plugin error: " + e.toString() + ". You can contact the plugin developer to report a bug.");
            return null;
        }
    }
}
