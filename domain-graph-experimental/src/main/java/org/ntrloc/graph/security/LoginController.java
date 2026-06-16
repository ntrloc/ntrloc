package org.ntrloc.graph.security;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class LoginController {

    @GetMapping(value = "/login",  produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> loginPage() {

        return Mono.just("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>ntrloc Login</title>
                    <style>
                        body {
                            font-family: sans-serif;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            height: 100vh;
                            margin: 0;
                            background-color: #f5f5f5;
                        }
                        .login-container {
                            background: white;
                            padding: 2rem;
                            border-radius: 8px;
                            box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                            width: 360px;
                        }
                        h2 { text-align: center; margin-bottom: 1.5rem; }
                        .form-group { margin-bottom: 1rem; }
                        label { display: block; margin-bottom: 0.25rem; font-size: 0.9rem; }
                        input {
                            width: 100%;
                            padding: 0.5rem;
                            border: 1px solid #ccc;
                            border-radius: 4px;
                            box-sizing: border-box;
                        }
                        .btn {
                            width: 100%;
                            padding: 0.6rem;
                            border: none;
                            border-radius: 4px;
                            cursor: pointer;
                            font-size: 1rem;
                            margin-top: 0.5rem;
                        }
                        .btn-primary { background-color: #4a90e2; color: white; }
                        .btn-oauth { background-color: #e24a4a; color: white; }
                        .divider {
                            text-align: center;
                            margin: 1.5rem 0;
                            border-top: 1px solid #eee;
                            line-height: 0;
                        }
                        .divider span {
                            background: white;
                            padding: 0 0.5rem;
                            color: #999;
                            font-size: 0.85rem;
                        }
                        .error {
                            color: red;
                            font-size: 0.85rem;
                            margin-bottom: 1rem;
                            text-align: center;
                            display: none;
                        }
                    </style>
                </head>
                <body>
                <div class="login-container">
                    <h2>ntrloc</h2>
                
                    <div id="error" class="error">
                        Invalid username or password.
                    </div>
                
                    <form action="/login" method="post">
                        <div class="form-group">
                            <label for="username">Username</label>
                            <input type="text" id="username" name="username" placeholder="Username" required />
                        </div>
                        <div class="form-group">
                            <label for="password">Password</label>
                            <input type="password" id="password" name="password" placeholder="Password" required />
                        </div>
                        <button type="submit" class="btn btn-primary">Sign In</button>
                    </form>
                
                    <div class="divider"><span>or</span></div>
                
                    <a href="/oauth2/authorization/keycloak">
                        <button type="button" class="btn btn-oauth">Sign In with Keycloak</button>
                    </a>
                </div>
               
                </body>
                </html>
            """);
    }

}
