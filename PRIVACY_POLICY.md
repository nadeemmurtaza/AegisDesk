# Privacy Policy for Aegis Assistant

**Last updated:** August 2026

Aegis is an on-device, privacy-first Android assistant. This policy outlines how we handle your data.

## 1. On-Device Processing
Aegis is designed to process your data **locally on your device**. All machine learning inference, including natural language processing, visual analysis (OCR), and audio transcription, happens directly on your smartphone. We do not transmit your personal conversations, screenshots, or notifications to the cloud for processing by default.

## 2. Data We Access
To function as an assistant, Aegis requests permissions to access certain device APIs:
- **Notifications:** To summarize your incoming messages and suggest replies.
- **Accessibility:** To read on-screen text when you explicitly request contextual help.
- **Media Projection (Screen Capture):** To capture your screen only when you manually trigger the "Aegis: Understand Screen" Quick Settings tile.
- **Microphone:** To listen to your voice commands.
- **Calendar & Contacts:** To manage your schedule and communicate with your contacts.

## 3. Data Retention
Aegis maintains an encrypted, local Vector Database on your device to form its "Memory". This database stores transcriptions of conversations and contextual summaries. This data NEVER leaves your device. You can clear this memory at any time within the app's Settings menu.

## 4. Third-Party Services
Aegis may allow you to download optional, open-weight language models (e.g., from Hugging Face) to improve performance. The act of downloading these model weights connects to third-party servers, but the models themselves run entirely offline once downloaded.

## 5. Security
Because your data does not leave your device, it is secured by Android's native disk encryption and sandbox protections. Aegis additionally requires biometric authentication for any high-risk actions (e.g., deleting a file, sending a message, posting to social media).

## 6. Changes to This Policy
If we introduce opt-in cloud features in the future, this policy will be updated to reflect exactly what data is transmitted, and such features will always require your explicit consent.

## 7. Contact Us
For questions regarding this privacy policy, please open an issue on our GitHub repository.
