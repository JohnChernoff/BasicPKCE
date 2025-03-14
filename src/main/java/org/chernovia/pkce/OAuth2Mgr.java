package org.chernovia.pkce;

import java.awt.Desktop;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.Random;
import java.util.stream.Collectors;
import com.sun.net.httpserver.HttpServer;

/**
 * This is a slightly broader implementation of https://github.com/tors42/lichess-oauth-pkce-app/blob/main/LichessPKCE.java
 */
public class OAuth2Mgr {

    final String oauthUri, tokenUri;

    public OAuth2Mgr(String authUri, String tokUri) {
        oauthUri = authUri;
        tokenUri = tokUri;
    }

    public String getToken(String clientId, String clientSecret, String scope, int localPort, boolean forceVerify) throws Exception { //String redirectUri,

        // Set up a local bind address which we will use in redirect_uri
        InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort);
        HttpServer httpServer = HttpServer.create(local, 0);
        String redirectHost = "localhost";
        String code_verifier = generateRandomCodeVerifier();
        String code_challenge_method = "S256";
        String code_challenge = generateCodeChallenge(code_verifier);
        String response_type = "code";
        String redirect_uri = "http://" + redirectHost + ":" +  localPort;
        System.out.println("Redirecting to: " + redirect_uri);
        String state = generateRandomState();

        Map<String,String> parameters = Map.of(
                "force_verify",forceVerify ? "true" : "false",
                "code_challenge_method", code_challenge_method,
                "code_challenge", code_challenge,
                "response_type", response_type,
                "client_id", clientId,
                "redirect_uri", redirect_uri,
                "scope", scope,
                "state", state
        );

        String paramString = parameters.entrySet().stream()
                .map(kv -> kv.getKey() + "=" + kv.getValue())
                .collect(Collectors.joining("&"));

        // Front Channel URL, all these parameters are non-sensitive.
        // The actual authentication between User and authenticator occurs outside of this demo application,
        // i.e. in the browser over HTTPS.
        URI frontChannelUrl = URI.create(oauthUri + "?" + paramString);

        // Prepare for handling the upcoming redirect,
        // after User has authenticated,
        // and granted this demo application permission
        // to fetch the e-mail address.
        // The random code_verifier we generated for this single login,
        // will be returned on a "Back Channel" so they can verify that
        // the Front Channel request really came from us.
        CompletableFuture<String> cf = registerRedirectHandler(httpServer, parameters, code_verifier, clientSecret);

        // Now we let the User authorize, using their browser
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(frontChannelUrl);
            } else {
                System.out.format("%s%n%n%s%n  %s%n%n",
                        "Doh, Desktop.Action.BROWSE not supported...",
                        "Could you manually go to the following URL :) ?",
                        frontChannelUrl);
            }
        } else {
            System.out.format("%s%n%n%s%n  %s%n%n",
                    "Doh, Desktop not supported...",
                    "Could you manually go to the following URL :) ?",
                    frontChannelUrl);
        }

        // Blocking until user has authorized,
        // and we've exchanged the incoming authorization code for an access token
        String access_token = cf.get();

        httpServer.stop(0);

        return access_token;
    }

    public void logout(String access_token) throws Exception {
        HttpRequest logoutRequest = HttpRequest.newBuilder(URI.create(oauthUri))
                .DELETE()
                .header("authorization", "Bearer " + access_token)
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient().send(logoutRequest, BodyHandlers.discarding());
        int statusCode = response.statusCode();
        if (statusCode != 204) {
            System.out.println("/api/token - " + response.statusCode());
        }
    }

    private String generateRandomCodeVerifier() {
        byte[] bytes = new byte[32];
        new Random().nextBytes(bytes);
        String code_verifier = encodeToString(bytes);
        return code_verifier;
    }

    private String generateCodeChallenge(String code_verifier) {
        byte[] asciiBytes = code_verifier.getBytes(StandardCharsets.US_ASCII);
        MessageDigest md;

        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException nsa_ehhh) {
            throw new RuntimeException(nsa_ehhh);
        }

        byte[] s256bytes = md.digest(asciiBytes);

        String code_challenge = encodeToString(s256bytes);
        return code_challenge;
    }

    private String generateRandomState() {
        byte[] bytes = new byte[16];
        new Random().nextBytes(bytes);
        // Not sure how long the parameter "should" be,
        // going for 8 characters here...
        return encodeToString(bytes).substring(0,8);
    }

    private String encodeToString(byte[] bytes) {
        return Base64.getUrlEncoder().encodeToString(bytes)
                .replaceAll(  "=",  "")
                .replaceAll("\\+", "-")
                .replaceAll("\\/", "_");
    }

    CompletableFuture<String> registerRedirectHandler(HttpServer httpServer, Map<String, String> requestParams, String code_verifier, String client_secret) {
        CompletableFuture<String> cf = new CompletableFuture<String>();
        httpServer.createContext("/",
                (exchange) -> {
                    httpServer.removeContext("/");

                    // The redirect arrives...
                    String query = exchange
                            .getRequestURI()
                            .getQuery();

                    Map<String,String> inparams = Arrays.stream(query.split("&"))
                            .collect(Collectors.toMap(
                                    s -> s.split("=")[0],
                                    s -> s.split("=")[1]
                            ));

                    String code = inparams.get("code");
                    String state = inparams.get("state");

                    if (! state.equals(requestParams.get("state"))) {
                        cf.completeExceptionally(new Exception("The \"state\" parameter we sent and the one we recieved didn't match!"));
                        return;
                    }

                    if (code == null) {
                        exchange.sendResponseHeaders(503, -1);
                        cf.completeExceptionally(new Exception("Authorization Failed"));
                        return;
                    }

                    // We have received metadata from Lichess,
                    // about the fact that the User has authorized us - yay!

                    // Let's respond with a nice HTML page in celebration.
                    byte[] responseBytes = "<html><body><h1>Success, you may close this page</h1></body></html>".getBytes();
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);


                    // Now,
                    // let's go ask for a token - using the metadata we've received
                    Map<String,String> tokenParameters = Map.of(
                            "code_verifier", code_verifier,
                            "grant_type", "authorization_code",
                            "code", code,
                            "redirect_uri", requestParams.get("redirect_uri"),
                            "client_id", requestParams.get("client_id"),
                            "client_secret", client_secret
                    );

                    String tokenParamsString = tokenParameters.entrySet().stream()
                            .map(kv -> kv.getKey() + "=" + kv.getValue())
                            .collect(Collectors.joining("&"));

                    HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create(tokenUri))
                            .POST(BodyPublishers.ofString(tokenParamsString))
                            .header("content-type", "application/x-www-form-urlencoded")
                            .build();

                    try {
                        HttpResponse<String> response = HttpClient.newHttpClient().send(tokenRequest, BodyHandlers.ofString());
                        int statusCode = response.statusCode();
                        String body = response.body();

                        if (statusCode != 200) {
                            System.out.println("/api/token - " + statusCode);
                        }
                        String access_token = parseField("access_token", body);

                        if (access_token == null) {
                            System.out.println("Body: " + body);
                            cf.completeExceptionally(new Exception("Authorization Failed"));
                            return;
                        }

                        // Ok, we have successfully retrieved a token which we can use
                        cf.complete(access_token);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
        httpServer.start();
        return cf;
    }

    // Light-weight fragile "json" ""parser""...
    private String parseField(String field, String body) {
        try {
            int start = body.indexOf(field) + field.length() + 3;
            int stop = body.indexOf("\"", start);
            return body.substring(start, stop);
        } catch (Exception e){
            return null;
        }
    }
}
