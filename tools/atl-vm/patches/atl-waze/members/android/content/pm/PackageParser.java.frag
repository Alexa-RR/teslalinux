	/**
	 * Certificates from the APK Signing Block (APK Signature Scheme v3, else v2), or null when the APK
	 * has neither. With validate the signatures and content digests are verified as the platform
	 * does at install time; otherwise the signer certificates are only parsed (ATL default, same as
	 * its handling of v1). Like AOSP, the signing certificate is the first one of each signer chain.
	 */
	private Certificate[] collectApkSigningBlockCertificates(Package pkg, boolean validate) {
		try {
			android.util.apk.ApkSignatureSchemeV3Verifier.VerifiedSigner v3 = validate
				? android.util.apk.ApkSignatureSchemeV3Verifier.verify(mArchiveSourcePath)
				: android.util.apk.ApkSignatureSchemeV3Verifier.unsafeGetCertsWithoutVerification(mArchiveSourcePath);
			Slog.i(TAG, "Package " + pkg.packageName + ": APK Signature Scheme v3" + (validate ? " (verified)" : ""));
			return new Certificate[] { v3.certs[0] };
		} catch (android.util.apk.SignatureNotFoundException e) {
			// no v3 block: try v2
		} catch (Exception e) {
			Slog.w(TAG, "APK Signature Scheme v3 failed for " + mArchiveSourcePath, e);
			return null;
		}
		try {
			java.security.cert.X509Certificate[][] signers = validate
				? android.util.apk.ApkSignatureSchemeV2Verifier.verify(mArchiveSourcePath)
				: android.util.apk.ApkSignatureSchemeV2Verifier.unsafeGetCertsWithoutVerification(mArchiveSourcePath);
			Certificate[] certs = new Certificate[signers.length];
			for (int i = 0; i < signers.length; i++)
				certs[i] = signers[i][0];
			Slog.i(TAG, "Package " + pkg.packageName + ": APK Signature Scheme v2" + (validate ? " (verified)" : ""));
			return certs;
		} catch (android.util.apk.SignatureNotFoundException e) {
			return null;
		} catch (Exception e) {
			Slog.w(TAG, "APK Signature Scheme v2 failed for " + mArchiveSourcePath, e);
			return null;
		}
	}

	/** The {@code split} names of the given split APKs, read from their manifests (AOSP ApplicationInfo.splitNames). */
	public static String[] readSplitNames(android.content.res.AssetManager assets, String[] splitPaths) {
		String[] names = new String[splitPaths.length];
		for (int i = 0; i < splitPaths.length; i++) {
			android.content.res.XmlResourceParser parser = null;
			try {
				parser = assets.openXmlResourceParser(assets.findCookieForPath(splitPaths[i]), "AndroidManifest.xml");
				int type;
				while ((type = parser.next()) != org.xmlpull.v1.XmlPullParser.START_TAG && type != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
				}
				names[i] = parser.getAttributeValue(null, "split");
			} catch (Exception e) {
				Slog.w(TAG, "could not read the split name of " + splitPaths[i] + ": " + e);
			} finally {
				if (parser != null)
					parser.close();
			}
			if (names[i] == null) { // bundletool naming: split_<name>.apk
				String f = new java.io.File(splitPaths[i]).getName();
				names[i] = f.startsWith("split_") && f.endsWith(".apk") ? f.substring(6, f.length() - 4) : f;
			}
		}
		return names;
	}
