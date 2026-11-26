/*
 * Copyright (C) 2026 Oleg Hudiakov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.github.ashadow22.omegat.deepl;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import org.omegat.core.Core;
import org.omegat.core.data.IProject;
import org.omegat.core.data.SourceTextEntry;
import org.omegat.core.machinetranslators.BaseCachedTranslate;
import org.omegat.util.Language;
import org.omegat.util.Log;
import org.omegat.util.PatternConsts;
import org.omegat.util.Preferences;

public class DeepLTranslateAPI extends BaseCachedTranslate {

    private static final String PREF_ALLOW_DEEPL = "allow_deepl_translate_api";
    private static final String PREF_API_KEY = "deepl_api_key";
    private static final String PREF_FORMALITY = "deepl_api_formality";
    private static final String PREF_MODEL_TYPE = "deepl_api_model_type";
    private static final String PREF_USE_CONTEXT = "deepl_api_use_context";
    private static final String PREF_SHOW_QUOTA = "deepl_api_show_quota";

    private static final String URL_FREE_TRANSLATE = "https://api-free.deepl.com/v2/translate";
    private static final String URL_PRO_TRANSLATE = "https://api.deepl.com/v2/translate";
    private static final String URL_FREE_USAGE = "https://api-free.deepl.com/v2/usage";
    private static final String URL_PRO_USAGE = "https://api.deepl.com/v2/usage";

    private static final Set<String> FORMALITY_SUPPORTED_LANGS = Set.of(
            "DE", "FR", "IT", "ES", "NL", "PL", "PT-BR", "PT-PT", "JA", "RU", "VI"
    );

    private static final Pattern RE_HTML = Pattern.compile("&#([0-9]+);");
    private static final char QUOTE = '"';
    private static final char BACKSLASH = '\\';

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final Object rateLock = new Object();
    private long lastRequestTime = 0;

    private volatile long cachedRemaining = -1;
    private volatile long cachedLimit = -1;
    private volatile long lastUsageCheck = 0;

    public static void loadPlugins() {
        Core.registerMachineTranslationClass(DeepLTranslateAPI.class);
    }

    public static void unloadPlugins() {
        /* empty */
    }

    @Override
    public String getName() {
        boolean showQuota = Preferences.isPreferenceDefault(PREF_SHOW_QUOTA, true);
        if (showQuota && cachedRemaining >= 0 && cachedLimit > 0) {
            NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
            return "DeepL Translate API (" + nf.format(cachedRemaining) + " / " + nf.format(cachedLimit) + " left)";
        }
        return "DeepL Translate API";
    }

    @Override
    protected String getPreferenceName() {
        return PREF_ALLOW_DEEPL;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public void showConfigurationUI(Window parent) {
        String currentKey = Preferences.getPreferenceDefault(PREF_API_KEY, "");
        String currentFormality = Preferences.getPreferenceDefault(PREF_FORMALITY, "default");
        String currentModelType = Preferences.getPreferenceDefault(PREF_MODEL_TYPE, "default");
        boolean currentUseContext = Preferences.isPreferenceDefault(PREF_USE_CONTEXT, true);
        boolean currentShowQuota = Preferences.isPreferenceDefault(PREF_SHOW_QUOTA, true);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("DeepL API Key:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JPasswordField keyField = new JPasswordField(currentKey, 30);
        panel.add(keyField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Formality:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        String[] formalities = { "default", "more", "less", "prefer_more", "prefer_less" };
        JComboBox<String> formalityBox = new JComboBox<>(formalities);
        formalityBox.setSelectedItem(currentFormality);
        panel.add(formalityBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Model Type:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        String[] modelTypes = { "default", "prefer_quality_optimized", "quality_optimized", "latency_optimized" };
        JComboBox<String> modelBox = new JComboBox<>(modelTypes);
        modelBox.setSelectedItem(currentModelType);
        panel.add(modelBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        JCheckBox useContextBox = new JCheckBox("Send surrounding segments as context (free of quota)", currentUseContext);
        panel.add(useContextBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        JCheckBox showQuotaBox = new JCheckBox("Show remaining character quota in Machine Translation pane", currentShowQuota);
        panel.add(showQuotaBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        JPanel usagePanel = new JPanel(new BorderLayout(8, 0));
        JButton checkBtn = new JButton("Check Usage");
        JLabel usageLabel = new JLabel(formatCachedUsage());
        usagePanel.add(checkBtn, BorderLayout.WEST);
        usagePanel.add(usageLabel, BorderLayout.CENTER);
        panel.add(usagePanel, gbc);

        checkBtn.addActionListener(e -> {
            String keyToCheck = new String(keyField.getPassword()).trim();
            if (keyToCheck.isEmpty()) {
                usageLabel.setText("Enter API key first");
                return;
            }
            checkBtn.setEnabled(false);
            usageLabel.setText("Checking...");
            new Thread(() -> {
                String statusText = fetchUsageInfo(keyToCheck);
                SwingUtilities.invokeLater(() -> {
                    usageLabel.setText(statusText);
                    checkBtn.setEnabled(true);
                });
            }).start();
        });

        int result = JOptionPane.showConfirmDialog(
                parent,
                panel,
                "DeepL Translate API Configuration",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        if (result == JOptionPane.OK_OPTION) {
            String newKey = new String(keyField.getPassword()).trim();
            String newFormality = (String) formalityBox.getSelectedItem();
            String newModelType = (String) modelBox.getSelectedItem();
            Preferences.setPreference(PREF_API_KEY, newKey);
            Preferences.setPreference(PREF_FORMALITY, newFormality != null ? newFormality : "default");
            Preferences.setPreference(PREF_MODEL_TYPE, newModelType != null ? newModelType : "default");
            Preferences.setPreference(PREF_USE_CONTEXT, useContextBox.isSelected());
            Preferences.setPreference(PREF_SHOW_QUOTA, showQuotaBox.isSelected());
            Preferences.save();
            if (!newKey.isEmpty()) {
                new Thread(() -> fetchUsageInfo(newKey)).start();
            }
        }
    }

    private String formatCachedUsage() {
        if (cachedRemaining >= 0 && cachedLimit > 0) {
            NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
            long used = cachedLimit - cachedRemaining;
            return "Used: " + nf.format(used) + " / " + nf.format(cachedLimit) + " (Remaining: " + nf.format(cachedRemaining) + ")";
        }
        return "Click 'Check Usage' to view remaining quota";
    }

    private String fetchUsageInfo(String apiKey) {
        try {
            String usageUrl = apiKey.endsWith(":fx") ? URL_FREE_USAGE : URL_PRO_USAGE;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(usageUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "DeepL-Auth-Key " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return "Error (" + resp.statusCode() + ")";
            }
            String body = resp.body();
            long count = extractJsonLong(body, "character_count");
            long limit = extractJsonLong(body, "character_limit");
            if (count >= 0 && limit > 0) {
                cachedLimit = limit;
                cachedRemaining = Math.max(0, limit - count);
                lastUsageCheck = System.currentTimeMillis();
                return formatCachedUsage();
            }
            return "Unexpected response";
        } catch (Exception ex) {
            Log.log(ex);
            return "Failed: " + ex.getMessage();
        }
    }

    @Override
    protected String translate(Language sLang, Language tLang, String text) throws Exception {
        String apiKey = Preferences.getPreferenceDefault(PREF_API_KEY, "").trim();
        if (apiKey.isEmpty()) {
            return "DeepL API key is not configured (Options -> Machine Translation -> Configure)";
        }

        String prev = getFromCache(sLang, tLang, text);
        if (prev != null) {
            return prev;
        }

        String endpoint = apiKey.endsWith(":fx") ? URL_FREE_TRANSLATE : URL_PRO_TRANSLATE;
        String sourceLang = mapSourceLanguage(sLang);
        String targetLang = mapTargetLanguage(tLang);
        String formality = Preferences.getPreferenceDefault(PREF_FORMALITY, "default");
        String modelType = Preferences.getPreferenceDefault(PREF_MODEL_TYPE, "default");
        boolean useContext = Preferences.isPreferenceDefault(PREF_USE_CONTEXT, true);

        StringBuilder form = new StringBuilder();
        form.append("text=").append(URLEncoder.encode(text, StandardCharsets.UTF_8));
        if (!sourceLang.isEmpty()) {
            form.append("&source_lang=").append(URLEncoder.encode(sourceLang, StandardCharsets.UTF_8));
        }
        form.append("&target_lang=").append(URLEncoder.encode(targetLang, StandardCharsets.UTF_8));
        form.append("&tag_handling=xml");

        if (!"default".equals(formality) && FORMALITY_SUPPORTED_LANGS.contains(targetLang)) {
            form.append("&formality=").append(URLEncoder.encode(formality, StandardCharsets.UTF_8));
        }

        if (!"default".equals(modelType)) {
            form.append("&model_type=").append(URLEncoder.encode(modelType, StandardCharsets.UTF_8));
        }

        if (useContext) {
            String contextText = buildSurroundingContext(text);
            if (!contextText.isEmpty()) {
                form.append("&context=").append(URLEncoder.encode(contextText, StandardCharsets.UTF_8));
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "DeepL-Auth-Key " + apiKey)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = sendWithRetry(request);

        if (response.statusCode() == 456) {
            cachedRemaining = 0;
            return "DeepL Quota Exceeded (456)";
        }
        if (response.statusCode() != 200) {
            Log.logErrorRB("DeepL HTTP error " + response.statusCode() + ": " + response.body());
            return "DeepL API Error (" + response.statusCode() + ")";
        }

        String translated = extractTranslatedText(response.body());
        if (translated == null) {
            return null;
        }

        translated = cleanOmegaTTags(text, translated);
        putToCache(sLang, tLang, text, translated);

        if (cachedRemaining >= 0) {
            cachedRemaining = Math.max(0, cachedRemaining - text.length());
        }
        if (Preferences.isPreferenceDefault(PREF_SHOW_QUOTA, true)
                && (System.currentTimeMillis() - lastUsageCheck > 120_000L)) {
            new Thread(() -> fetchUsageInfo(apiKey)).start();
        }

        return translated;
    }

    private String buildSurroundingContext(String currentText) {
        try {
            IProject project = Core.getProject();
            if (project == null || !project.isProjectLoaded()) {
                return "";
            }
            List<SourceTextEntry> entries = project.getAllEntries();
            if (entries == null || entries.isEmpty()) {
                return "";
            }
            int foundIdx = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (currentText.equals(entries.get(i).getSrcText())) {
                    foundIdx = i;
                    break;
                }
            }
            if (foundIdx == -1) {
                return "";
            }
            StringBuilder ctx = new StringBuilder();
            int start = Math.max(0, foundIdx - 2);
            int end = Math.min(entries.size() - 1, foundIdx + 1);
            for (int i = start; i <= end; i++) {
                if (i == foundIdx) {
                    continue;
                }
                String s = entries.get(i).getSrcText();
                if (s != null && !s.isBlank()) {
                    if (ctx.length() > 0) {
                        ctx.append(" ");
                    }
                    ctx.append(s.trim());
                }
            }
            String res = ctx.toString();
            return res.length() > 1500 ? res.substring(0, 1500) : res;
        } catch (Exception e) {
            return "";
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
        int maxRetries = 3;
        long backoffMs = 1200L;
        HttpResponse<String> response = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            synchronized (rateLock) {
                long now = System.currentTimeMillis();
                long wait = 350L - (now - lastRequestTime);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastRequestTime = System.currentTimeMillis();
            }

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code != 429 && code != 529) {
                return response;
            }
            if (attempt < maxRetries) {
                Thread.sleep(backoffMs);
                backoffMs *= 2;
            }
        }
        return response;
    }

    private static String mapSourceLanguage(Language lang) {
        String code = lang.getLanguageCode().toUpperCase(Locale.ROOT);
        if ("NB".equals(code) || "NN".equals(code)) {
            return "NO";
        }
        return code;
    }

    private static String mapTargetLanguage(Language lang) {
        String full = lang.getLanguage().toUpperCase(Locale.ROOT).replace('_', '-');
        if (full.equals("EN-GB") || full.equals("EN-US") || full.equals("PT-BR") || full.equals("PT-PT")) {
            return full;
        }
        if (full.startsWith("ZH-TW") || full.startsWith("ZH-HK") || full.startsWith("ZH-HANT")) {
            return "ZH-HANT";
        }
        if (full.startsWith("ZH")) {
            return "ZH-HANS";
        }
        String code = lang.getLanguageCode().toUpperCase(Locale.ROOT);
        if ("NB".equals(code) || "NN".equals(code)) {
            return "NO";
        }
        return code;
    }

    private static long extractJsonLong(String json, String key) {
        String marker = QUOTE + key + QUOTE + ":";
        int idx = json.indexOf(marker);
        if (idx == -1) {
            return -1;
        }
        int i = idx + marker.length();
        int len = json.length();
        while (i < len && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ':')) {
            i++;
        }
        StringBuilder num = new StringBuilder();
        while (i < len && Character.isDigit(json.charAt(i))) {
            num.append(json.charAt(i));
            i++;
        }
        return num.length() > 0 ? Long.parseLong(num.toString()) : -1;
    }

    private static String extractTranslatedText(String json) {
        String marker = QUOTE + "text" + QUOTE + ":";
        int idx = json.indexOf(marker);
        if (idx == -1) {
            return null;
        }
        int i = idx + marker.length();
        int len = json.length();
        while (i < len && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= len || json.charAt(i) != QUOTE) {
            return null;
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < len) {
            char c = json.charAt(i);
            if (c == BACKSLASH && i + 1 < len) {
                char next = json.charAt(i + 1);
                if (next == 'u' && i + 5 < len) {
                    String hex = json.substring(i + 2, i + 6);
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 6;
                    continue;
                } else if (next == 'n') {
                    sb.append('\n');
                } else if (next == 'r') {
                    sb.append('\r');
                } else if (next == 't') {
                    sb.append('\t');
                } else {
                    sb.append(next);
                }
                i += 2;
                continue;
            }
            if (c == QUOTE) {
                break;
            }
            sb.append(c);
            i++;
        }
        String res = sb.toString();
        while (true) {
            Matcher m = RE_HTML.matcher(res);
            if (!m.find()) {
                break;
            }
            String g = m.group();
            char c = (char) Integer.parseInt(m.group(1));
            res = res.replace(g, Character.toString(c));
        }
        return res;
    }

    private static String cleanOmegaTTags(String source, String translated) {
        String newTr = translated;
        Matcher tag = PatternConsts.OMEGAT_TAG_SPACE.matcher(newTr);
        while (tag.find()) {
            String searchTag = tag.group();
            if (!source.contains(searchTag)) {
                String replacement = searchTag.substring(0, searchTag.length() - 1);
                newTr = newTr.replace(searchTag, replacement);
            }
        }
        tag = PatternConsts.SPACE_OMEGAT_TAG.matcher(newTr);
        while (tag.find()) {
            String searchTag = tag.group();
            if (!source.contains(searchTag)) {
                String replacement = searchTag.substring(1);
                newTr = newTr.replace(searchTag, replacement);
            }
        }
        return newTr;
    }
}
