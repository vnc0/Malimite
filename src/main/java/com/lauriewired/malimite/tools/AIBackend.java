package com.lauriewired.malimite.tools;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.lauriewired.malimite.configuration.Config;
import com.lauriewired.malimite.ui.AnalysisWindow;
import com.lauriewired.malimite.configuration.Project;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AIBackend {
    public static class Model {
        private final String displayName;
        private final String provider;
        private final String modelId;

        public Model(String displayName, String provider, String modelId) {
            this.displayName = displayName;
            this.provider = provider;
            this.modelId = modelId;
        }

        public String getDisplayName() { return displayName; }
        public String getProvider() { return provider; }
        public String getModelId() { return modelId; }
    }

    private static final Model[] SUPPORTED_MODELS = {
        new Model("OpenAI GPT-4 Turbo", "openai", "gpt-4-turbo"),
        new Model("OpenAI GPT-4 Mini", "openai", "gpt-4-mini")        
        

        // TODO: Add support for Claude and Local Model
        //new Model("Claude", "claude", "claude-v1"),
        //new Model("Local Model", "local", "local")
    };

    public static Model[] getSupportedModels() {
        return SUPPORTED_MODELS;
    }

    public static Model getDefaultModel() {
        return SUPPORTED_MODELS[0]; // Returns GPT-4 Turbo as default
    }

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/complete";

    private static final String DEFAULT_PROMPT = 
        "Translate the following decompiled functions into %s. " +
        "Return only the %s code for these functions, preserving the method names and any global variables. " +
        "You may adjust local variable names for readability, but do not add, remove, or modify any other methods or global definitions. " +
        "Surround each translated function with \"BEGIN_FUNCTION\" at the beginning and \"END_FUNCTION\" at the end. " +
        "Keep functions in the same order as they appear in the original code.";

    public static String getDefaultPrompt() {
        Project currentProject = AnalysisWindow.getCurrentProject();
        String targetLanguage = currentProject != null && currentProject.isSwift() ? "Swift" : "Objective-C";
        return String.format(DEFAULT_PROMPT, targetLanguage, targetLanguage);
    }

    public static String sendToModel(String provider, String modelId, String inputText, Config config) throws IOException {
        String apiUrl;
        String apiKey = null;

        switch (provider.toLowerCase()) {
            case "openai":
                apiUrl = OPENAI_API_URL;
                apiKey = config.getOpenAIApiKey();
                return sendOpenAIRequest(apiUrl, apiKey, inputText, modelId);

            case "claude":
                apiUrl = CLAUDE_API_URL;
                apiKey = config.getClaudeApiKey();
                return sendClaudeRequest(apiUrl, apiKey, inputText, modelId);

            case "local":
                apiUrl = config.getLocalModelUrl();
                return sendLocalModelRequest(apiUrl, inputText);

            default:
                throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
    }

    private static String sendOpenAIRequest(String apiUrl, String apiKey, String inputText, String modelId) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        // Properly escape the input text for JSON
        String escapedInput = inputText.replace("\\", "\\\\")
                                     .replace("\"", "\\\"")
                                     .replace("\n", "\\n")
                                     .replace("\r", "\\r")
                                     .replace("\t", "\\t")
                                     .replace("\f", "\\f")
                                     .replace("\b", "\\b");

        // Construct the JSON payload using a more structured approach
        String jsonInputString = String.format(
            "{" +
                "\"model\": \"%s\"," +
                "\"messages\": [" +
                    "{" +
                        "\"role\": \"user\"," +
                        "\"content\": \"%s\"" +
                    "}" +
                "]" +
            "}", modelId, escapedInput);

        String response = executeRequest(conn, jsonInputString);
        return parseOpenAIResponse(response);
    }

    private static String parseOpenAIResponse(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            JSONArray choices = json.getJSONArray("choices");
            if (choices.length() > 0) {
                JSONObject firstChoice = choices.getJSONObject(0);
                JSONObject message = firstChoice.getJSONObject("message");
                String content = message.getString("content");
                
                // Extract code between triple backticks
                int startIndex = content.indexOf("```");
                if (startIndex != -1) {
                    startIndex = content.indexOf("\n", startIndex) + 1;
                    int endIndex = content.lastIndexOf("```");
                    if (endIndex != -1) {
                        return content.substring(startIndex, endIndex).trim();
                    }
                }
                return content;
            }
        } catch (JSONException e) {
            System.err.println("Error parsing OpenAI response: " + e.getMessage());
        }
        return jsonResponse; // Return original response if parsing fails
    }

    private static String sendClaudeRequest(String apiUrl, String apiKey, String inputText, String modelId) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
    
        // Adjust payload format for Claude API if needed
        String jsonInputString = String.format(
            "{\"model\": \"%s\", \"prompt\": \"%s\"}",
            modelId, inputText
        );
    
        return executeRequest(conn, jsonInputString);
    }    

    private static String sendLocalModelRequest(String apiUrl, String inputText) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String jsonInputString = "{\"input\": \"" + inputText + "\"}";
        return executeRequest(conn, jsonInputString);
    }

    private static String executeRequest(HttpURLConnection conn, String jsonInputString) throws IOException {
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
    
        int responseCode = conn.getResponseCode();
        InputStream inputStream = (responseCode >= 200 && responseCode < 300) ?
            conn.getInputStream() : conn.getErrorStream();
    
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            return response.toString();
        }
    }    
}

