		/* the remaining builder options Android apps set; recorded, not enforced (no biometrics/StrongBox here) */
		public Builder setRandomizedEncryptionRequired(boolean required) { return this; }
		public Builder setDigests(String... digests) { return this; }
		public Builder setSignaturePaddings(String... paddings) { return this; }
		public Builder setUserAuthenticationValidityDurationSeconds(int seconds) { return this; }
		public Builder setUserAuthenticationParameters(int timeout, int type) { return this; }
		public Builder setUserAuthenticationValidWhileOnBody(boolean remainsValid) { return this; }
		public Builder setInvalidatedByBiometricEnrollment(boolean invalidate) { return this; }
		public Builder setUnlockedDeviceRequired(boolean required) { return this; }
		public Builder setIsStrongBoxBacked(boolean isStrongBoxBacked) { if (isStrongBoxBacked) throw new IllegalArgumentException("StrongBox is not available on this device"); return this; }
		public Builder setKeyValidityStart(java.util.Date start) { return this; }
		public Builder setKeyValidityEnd(java.util.Date end) { return this; }
		public Builder setKeyValidityForOriginationEnd(java.util.Date end) { return this; }
		public Builder setKeyValidityForConsumptionEnd(java.util.Date end) { return this; }
		public Builder setAttestationChallenge(byte[] challenge) { return this; }
		public Builder setCertificateSubject(javax.security.auth.x500.X500Principal subject) { return this; }
		public Builder setCertificateSerialNumber(java.math.BigInteger serial) { return this; }
		public Builder setCertificateNotBefore(java.util.Date date) { return this; }
		public Builder setCertificateNotAfter(java.util.Date date) { return this; }
		public Builder setAlgorithmParameterSpec(java.security.spec.AlgorithmParameterSpec spec) { return this; }
		public Builder setUserPresenceRequired(boolean required) { return this; }
		public Builder setUserConfirmationRequired(boolean required) { return this; }
		public Builder setMaxUsageCount(int maxUsageCount) { return this; }
