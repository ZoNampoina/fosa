# Bundled QR dependencies
`qr.js` contains qrcode 1.5.4 (MIT), @zxing/browser 0.1.5 (MIT) and @zxing/library (Apache 2.0), bundled with esbuild. They run locally, without CDN. License comments are retained in qr.js.
Rebuild: npm install qrcode@1.5.4 @zxing/browser@0.1.5 esbuild@0.25.10; esbuild the entry importing QRCode and BrowserQRCodeReader with --bundle --minify --legal-comments=eof. No third-party audio relay is used.
