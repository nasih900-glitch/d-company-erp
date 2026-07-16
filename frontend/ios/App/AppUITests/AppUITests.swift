import XCTest

final class AppUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testLoginScreenAppearsOnLaunch() throws {
        let app = XCUIApplication()
        app.launch()

        let emailField = app.textFields["login.email"]
        XCTAssertTrue(emailField.waitForExistence(timeout: 10), "Email field should appear on launch")

        let passwordField = app.secureTextFields["login.password"]
        XCTAssertTrue(passwordField.exists, "Password field should be visible")

        let signInButton = app.buttons["login.signIn"]
        XCTAssertTrue(signInButton.exists, "Sign in button should be visible")

        XCTAssertTrue(app.buttons["login.forgotPassword"].exists)
        XCTAssertTrue(app.buttons["login.newLogin"].exists)
    }

    func testForgotPasswordSwitchesToResetRequestMode() throws {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.textFields["login.email"].waitForExistence(timeout: 10))
        app.buttons["login.forgotPassword"].tap()

        // Reset-request mode: email field stays, password field disappears,
        // the primary action button is relabeled, and a back chevron appears.
        XCTAssertTrue(app.textFields["login.email"].waitForExistence(timeout: 5), "Email field should remain in reset mode")
        XCTAssertFalse(app.secureTextFields["login.password"].exists, "Password field should be hidden in reset mode")
        XCTAssertTrue(app.buttons["login.primaryAction"].waitForExistence(timeout: 5), "Reset mode should show the primary action button")
        XCTAssertTrue(app.buttons["login.back"].exists, "Back button should appear once off the login mode")

        app.buttons["login.back"].tap()

        XCTAssertTrue(app.secureTextFields["login.password"].waitForExistence(timeout: 5), "Password field should return after navigating back")
        XCTAssertTrue(app.buttons["login.signIn"].exists, "Sign-in button should return after navigating back")
        XCTAssertFalse(app.buttons["login.back"].exists, "Back button should disappear once back on the login screen")
    }

    func testNewLoginSwitchesToRegisterRequestMode() throws {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.textFields["login.email"].waitForExistence(timeout: 10))
        app.buttons["login.newLogin"].tap()

        // Register-request mode adds a name field on top of email, and hides
        // the plain sign-in password field until a confirm-password pair.
        XCTAssertTrue(app.textFields["login.name"].waitForExistence(timeout: 5), "Full name field should appear in register mode")
        XCTAssertTrue(app.textFields["login.email"].exists, "Email field should still be present in register mode")
        XCTAssertTrue(app.buttons["login.primaryAction"].waitForExistence(timeout: 5), "Register mode should show the primary action button")

        app.buttons["login.back"].tap()

        XCTAssertTrue(app.buttons["login.signIn"].waitForExistence(timeout: 5), "Sign-in button should return after navigating back")
        XCTAssertFalse(app.textFields["login.name"].exists, "Name field should disappear once back on the login screen")
    }

    func testSignInButtonDisabledUntilCredentialsEntered() throws {
        let app = XCUIApplication()
        app.launch()

        let emailField = app.textFields["login.email"]
        let passwordField = app.secureTextFields["login.password"]
        let signInButton = app.buttons["login.signIn"]
        XCTAssertTrue(emailField.waitForExistence(timeout: 10))

        // Empty form: sign-in must stay disabled (AppSession.login() should
        // never even be reachable with blank credentials).
        XCTAssertFalse(signInButton.isEnabled, "Sign-in should be disabled with no email/password entered")

        emailField.tap()
        emailField.typeText("reviewer@example.com")
        passwordField.tap()
        passwordField.typeText("not-the-real-password")

        XCTAssertTrue(signInButton.isEnabled, "Sign-in should enable once both fields are non-empty")
    }

    // App Store Connect screenshot capture. Reads the reviewer account from
    // the environment (DCOMPANY_REVIEWER_EMAIL / DCOMPANY_REVIEWER_PASSWORD)
    // rather than hardcoding it here — this file is committed to git and the
    // submission checklist explicitly says the demo account must never land
    // in a committed file. Output directory and a per-device filename prefix
    // (so an iPhone run and an iPad run don't clobber each other) also come
    // from the environment.
    func testCaptureAppStoreScreenshots() throws {
        // xcodebuild's TEST_RUNNER_ env-var passthrough isn't reaching the
        // simulator's test process in this Xcode version, so credentials and
        // config come from a plaintext file at a fixed /tmp path instead
        // (proven readable/writable from inside this process). The caller is
        // responsible for writing that file right before the run and
        // deleting it right after — it must never be committed to git.
        let configPath = "/tmp/dcompany-screenshot-config.txt"
        guard let configText = try? String(contentsOfFile: configPath, encoding: .utf8) else {
            throw XCTSkip("Write email/password/outputDir/prefix (one per line) to \(configPath) to run this test")
        }
        let lines = configText.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard lines.count >= 4 else {
            throw XCTSkip("\(configPath) must have 4 lines: email, password, outputDir, prefix")
        }
        let email = lines[0]
        let password = lines[1]
        let outputDir = lines[2]
        let prefix = lines[3]
        try FileManager.default.createDirectory(atPath: outputDir, withIntermediateDirectories: true)

        let app = XCUIApplication()

        // iOS's system Password AutoFill "Save Password?" prompt can appear
        // with unpredictable delay after a successful sign-in — checking once
        // right after the sign-in tap isn't reliable, since the prompt can
        // still pop up afterward and silently eat every subsequent tap it
        // intercepts (which is exactly what turned "3-pos" into another Home
        // screenshot the first time). Call this right before every capture.
        func dismissPasswordPromptIfPresent() {
            let notNowButton = app.buttons["Not Now"]
            if notNowButton.waitForExistence(timeout: 2) {
                notNowButton.tap()
            }
        }

        func capture(_ name: String) {
            dismissPasswordPromptIfPresent()
            let data = XCUIScreen.main.screenshot().pngRepresentation
            let path = "\(outputDir)/\(prefix)-\(name).png"
            FileManager.default.createFile(atPath: path, contents: data)
        }

        app.launch()

        // A prior run on this same simulator can leave a signed-in session
        // (AppSession restores from Keychain on launch), which would skip
        // straight past the login screen we need a screenshot of. Sign out
        // first whenever we land already-authenticated, so every run starts
        // from a guaranteed-clean login screen.
        let emailField = app.textFields["login.email"]
        if !emailField.waitForExistence(timeout: 8) {
            let accountMenuButton = app.buttons["Account"]
            XCTAssertTrue(accountMenuButton.waitForExistence(timeout: 15), "Expected either the login screen or a restored session with an Account menu")
            accountMenuButton.tap()
            let signOutButton = app.buttons["Sign out"]
            XCTAssertTrue(signOutButton.waitForExistence(timeout: 5), "Sign out option should appear in the account menu")
            signOutButton.tap()
        }
        XCTAssertTrue(emailField.waitForExistence(timeout: 15), "Login screen should appear")
        Thread.sleep(forTimeInterval: 1.0)
        capture("1-login")

        emailField.tap()
        emailField.typeText(email)
        let passwordField = app.secureTextFields["login.password"]
        passwordField.tap()
        passwordField.typeText(password)
        app.buttons["login.signIn"].tap()
        dismissPasswordPromptIfPresent()

        // Match tab items by identifier AND label together, not
        // .matching(identifier:) alone: "chart.bar" is not unique — Home's own
        // "Net Profit" KPI card icon uses the same SF Symbol, has no explicit
        // accessibility label, so it inherits the same identifier with an
        // auto-generated label ("Chart Column") instead of "Reports". Without
        // the label check, .firstMatch silently grabbed that inert KPI icon
        // instead of the real tab button, tapped nothing, and "5-reports"
        // turned out to still be a screenshot of Home. Also not
        // app.tabBars.buttons[...] — the custom .premiumTabChrome() styling
        // doesn't get wrapped in the standard tabBar accessibility container
        // on iPad's regular-width layout the way it does on iPhone's compact
        // layout, even though it's the same TabView underneath.
        func tabButton(identifier: String, label: String) -> XCUIElement {
            app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", identifier, label)).firstMatch
        }

        let homeTab = tabButton(identifier: "house", label: "Home")
        XCTAssertTrue(homeTab.waitForExistence(timeout: 20), "Should land on Home after sign-in")
        Thread.sleep(forTimeInterval: 1.5)
        dismissPasswordPromptIfPresent()
        capture("2-home")

        let posTab = tabButton(identifier: "cart", label: "POS")
        if posTab.waitForExistence(timeout: 5) {
            posTab.tap()
            Thread.sleep(forTimeInterval: 1.5)
            dismissPasswordPromptIfPresent()
            capture("3-pos")
        }

        let gamingTab = tabButton(identifier: "gamecontroller", label: "Gaming")
        if gamingTab.waitForExistence(timeout: 5) {
            gamingTab.tap()
            Thread.sleep(forTimeInterval: 1.5)
            dismissPasswordPromptIfPresent()
            capture("4-gaming")
        }

        let reportsTab = tabButton(identifier: "chart.bar", label: "Reports")
        if reportsTab.waitForExistence(timeout: 5) {
            reportsTab.tap()
            Thread.sleep(forTimeInterval: 1.5)
            dismissPasswordPromptIfPresent()
            capture("5-reports")
        }
    }
}
