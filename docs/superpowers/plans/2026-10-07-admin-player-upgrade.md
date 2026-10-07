# Admin and player v1.1.3 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement inline.

**Goal:** Upgrade owner controls and compact player UI, then publish v1.1.3.
**Architecture:** Extend existing settings table and admin/API routes. Android reads a public, allowlisted app configuration for optional donation and a menu sponsor card. Password hash remains server-only; transactional rotation revokes admin sessions.
**Tech Stack:** TypeScript, Postgres, vanilla JS/CSS, Android Kotlin/Media3.
**Spec:** User-approved conversation: compact ID list/details, current-password change, ads disabled by default, optional donation QR/number/copy/send-money instructions, compact dark settings with Admin login link, smaller controls and Stretch default.

## Global Constraints
- Preserve user/device authentication, playback, signing certificate and existing settings.
- No new hosting database; use existing settings table.
- Ads and donations disabled until configured; no payment processing or claims of confirmed payment.
- Public API must never return password hashes, sessions or private settings.
- Existing explicit screen choices survive; default remains Stretch.

## Review Focus
- Concurrent login versus password change must not leave old-password sessions valid.
- Revoked owner session must not change password.
- Oversized/malformed QR content and unsafe links must fail validation.
- Android configuration failure must preserve playback and hide optional features.
- Small screen / TV remote must scroll menu and reach all controls.

## Tasks
- [ ] Backend: test configuration validation and atomic password rotation; implement app settings and password routes; run unit/typecheck and isolated DB tests.
- [ ] Admin: compact accessible expandable user rows, password form, editable donation methods with QR upload and sponsor settings; browser check mobile/desktop.
- [ ] Android: compact controls/info, dark scrollable menu, admin external login link, donation number/QR/copy, optional sponsor card; build and tests.
- [ ] Review all changes, fix significant issues, commit/push PR and deploy backend; smoke check production, publish signed APK/OTA and verify hash/certificate.
