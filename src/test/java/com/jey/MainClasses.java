package com.jey;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// 규칙 테스트가 운영 코드 전체(com.jey, 테스트 제외)를 볼 때 쓴다. 한 번만 읽고 모든 규칙 테스트가 함께 쓴다.
// 테스트마다 따로 읽어 static으로 들고 있으면 그만큼 테스트 JVM의 힙을 차지한다.
public final class MainClasses {

	private MainClasses() {
	}

	public static JavaClasses get() {
		return Holder.MAIN;
	}

	// 처음 부를 때 읽는다(클래스 초기화는 JVM이 한 번만 하고 스레드에 안전하다).
	private static final class Holder {

		static final JavaClasses MAIN = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.jey");

	}

}
