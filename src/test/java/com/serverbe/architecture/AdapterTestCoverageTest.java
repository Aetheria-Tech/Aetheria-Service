package com.serverbe.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @responsibility 아웃바운드 포트 구현체가 <b>대응하는 단위 테스트를 갖고 있는지</b> 고정합니다.
 * @implSpec 규칙은 하나뿐입니다 — {@code adapter.out..} 에서 {@code application.port.out..} 을
 * 구현하는 클래스는 같은 이름에 {@code Test}를 붙인 클래스가 있어야 합니다.
 * <b>포트를 구현한다는 것은 바깥 세계와 대화한다는 뜻이고, 그 대화가 맞는지는 컴파일러가 봐 주지
 * 않습니다.</b>
 * @implNote <b>왜 {@code LayerDependencyTest}에 넣지 않았는가</b> — 그 클래스는
 * {@code @AnalyzeClasses(DoNotIncludeTests)}라 테스트 클래스를 아예 임포트하지 않습니다.
 * "대응하는 테스트가 있는가"는 테스트 클래스를 봐야 답할 수 있는 질문이라 임포트 설정이 정반대이고,
 * 성격도 계층 의존이 아니라 테스트 커버리지입니다. 그래서 {@code @ArchTest}가 아닌 평범한
 * {@code @Test}로 {@code ClassFileImporter}를 직접 씁니다.
 * @implNote <b>이 규칙은 좁게 시작해서 넓어졌습니다.</b> 11번에서 켤 때는
 * {@code adapter.out.persistence..} 만 덮었습니다 — 전체로 켜면 그 시점에 넷이 빨간불이었고,
 * <b>빨간 채로 두는 규칙은 아무도 믿지 않게 되기 때문</b>입니다. 좁혔다는 사실을 숨기지 않고
 * 12번 항목으로 열어 두었고, 그 항목이 닫히면서 지금 범위가 되었습니다.
 * 근거는 {@code docs/refactor/12-test-gaps-outbound-adapters.md}.
 */
@DisplayName("어댑터 테스트 커버리지")
class AdapterTestCoverageTest {

    private static final String 대상_패키지 = "com.serverbe.adapter.out";
    private static final String 아웃바운드_포트_패키지 = "com.serverbe.application.port.out";
    private static final String 프로파일_애노테이션 = "org.springframework.context.annotation.Profile";

    @Test
    @DisplayName("아웃바운드 포트 구현체는 대응하는 테스트를 가진다")
    void 아웃바운드_포트_구현체는_대응하는_테스트를_가진다() {
        JavaClasses 전체 = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("com.serverbe");

        Set<String> 테스트_이름들 = 전체.stream()
                .map(JavaClass::getSimpleName)
                .filter(name -> name.endsWith("Test"))
                .collect(Collectors.toSet());

        List<String> 테스트가_없는_구현체 = 전체.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith(대상_패키지))
                .filter(AdapterTestCoverageTest::구체_클래스다)
                .filter(AdapterTestCoverageTest::아웃바운드_포트를_구현한다)
                .filter(javaClass -> !상용_경로가_아닌_대역이다(javaClass))
                .map(JavaClass::getSimpleName)
                .filter(name -> !테스트_이름들.contains(name + "Test"))
                .sorted()
                .toList();

        assertThat(테스트가_없는_구현체)
                .as("""
                        아웃바운드 포트를 구현했는데 대응하는 *Test 가 없습니다.
                        포트를 구현한다는 것은 바깥 세계와 대화한다는 뜻이고, 그 대화가 맞는지는
                        컴파일러가 봐 주지 않습니다.
                        docs/refactor/11-test-gaps-persistence-adapters.md 와 12-test-gaps-outbound-adapters.md 를 보세요.""")
                .isEmpty();
    }

    private static boolean 구체_클래스다(JavaClass javaClass) {
        return !javaClass.isInterface()
                && !javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
                && !javaClass.isNestedClass();
    }

    private static boolean 아웃바운드_포트를_구현한다(JavaClass javaClass) {
        return javaClass.getAllRawInterfaces().stream()
                .anyMatch(port -> port.getPackageName().startsWith(아웃바운드_포트_패키지));
    }

    /**
     * {@code @Profile}이 붙은 구현체는 상용 경로에 뜨지 않는 <b>대역</b>입니다
     * ({@code FakeS3Adapter}·{@code MockS3AiOutputAdapter}·{@code FakeSageMakerAdapter}).
     * <b>그 자체가 테스트 대역이므로 테스트를 요구하면 "가짜가 가짜인지 확인하는 테스트"가 되고,
     * 그건 이 규칙이 피하려던 소음입니다.</b>
     *
     * @implNote 이름 규칙({@code Fake*}·{@code Mock*})이 아니라 <b>애노테이션</b>으로 거릅니다.
     * {@code @Profile}이 붙었다는 것은 "이 빈은 특정 환경에서만 뜬다"는 <b>선언</b>이지만, 이름은
     * 누구나 다르게 지을 수 있어 규칙이 이름 짓기 취향에 끌려다니게 됩니다.
     */
    private static boolean 상용_경로가_아닌_대역이다(JavaClass javaClass) {
        return javaClass.isAnnotatedWith(프로파일_애노테이션);
    }
}
