package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedTypeClosureValidatorTest {

    @Test
    void acceptsMethodsInheritedFromGeneratedServiceExternalBaseContract() {
        List<GeneratedFile> files = List.of(
                file("service/IVisitService.java", """
                        import org.springblade.core.mp.base.BaseService;
                        interface IVisitService extends BaseService<Visit> { }
                        """),
                file("controller/VisitController.java", """
                        class VisitController {
                            IVisitService service;
                            Object detail(Object query) { return service.getOne(query); }
                            boolean remove(java.util.List<Long> ids) { return service.deleteLogic(ids); }
                        }
                        """));

        List<GeneratedProjectValidator.Issue> issues = new GeneratedTypeClosureValidator().validate(files);
        assertTrue(issues.stream().noneMatch(issue -> "TYPE-METHOD-MISSING".equals(issue.rule())), issues::toString);
    }

    @Test
    void acceptsGeneratedSubtypeArgumentsAndInheritedLombokAccessors() {
        List<GeneratedFile> files = List.of(
                file("api/HotworkIVO.java", """
                        import lombok.Data;
                        @Data class HotworkIVO { private Long hotworkId; }
                        """),
                file("api/HotworkUVO.java", """
                        import lombok.Data;
                        @Data class HotworkUVO extends HotworkIVO { private Long id; }
                        """),
                file("impl/HotworkService.java", """
                        class HotworkService {
                            void modify(HotworkUVO value) {
                                Long id = value.getHotworkId();
                                convert(value);
                            }
                            void convert(HotworkIVO value) { }
                        }
                        """));

        List<GeneratedProjectValidator.Issue> issues = new GeneratedTypeClosureValidator().validate(files);
        assertTrue(issues.isEmpty(), issues::toString);
    }

    @Test
    void detectsGeneratedMethodGenericAndReturnContractBreaksWithoutPrivateDependencies() {
        List<GeneratedFile> files = List.of(
                file("blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/entity/SpecialPeriod.java", """
                        package org.springblade.specialperiod.entity;
                        import lombok.Data;
                        import org.springblade.core.mp.base.BaseEntity;
                        @Data public class SpecialPeriod extends BaseEntity { private String periodTypeCode; }
                        """),
                file("blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/vo/SpecialPeriodVO.java", """
                        package org.springblade.specialperiod.vo;
                        public class SpecialPeriodVO { }
                        """),
                file("blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/vo/evo/SpecialPeriodEVO.java", """
                        package org.springblade.specialperiod.vo.evo;
                        public class SpecialPeriodEVO { }
                        """),
                file("blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/vo/qvo/SpecialPeriodQVO.java", """
                        package org.springblade.specialperiod.vo.qvo;
                        public class SpecialPeriodQVO { }
                        """),
                file("blade-service/blade-specialperiod/src/main/java/org/springblade/specialperiod/mapper/SpecialPeriodMapper.java", """
                        package org.springblade.specialperiod.mapper;
                        import java.util.List;
                        import java.util.Map;
                        import com.baomidou.mybatisplus.core.metadata.IPage;
                        import org.springblade.specialperiod.vo.SpecialPeriodVO;
                        import org.springblade.specialperiod.vo.qvo.SpecialPeriodQVO;
                        public interface SpecialPeriodMapper {
                            List<SpecialPeriodVO> selectPageList(IPage<SpecialPeriodVO> page, SpecialPeriodQVO qvo);
                            Map<String,Object> selectStatByCondition(SpecialPeriodQVO qvo);
                        }
                        """),
                file("blade-service/blade-specialperiod/src/main/java/org/springblade/specialperiod/service/impl/SpecialPeriodServiceImpl.java", """
                        package org.springblade.specialperiod.service.impl;
                        import java.util.List;
                        import com.baomidou.mybatisplus.core.metadata.IPage;
                        import org.springblade.core.mp.base.BaseServiceImpl;
                        import org.springblade.specialperiod.entity.SpecialPeriod;
                        import org.springblade.specialperiod.mapper.SpecialPeriodMapper;
                        import org.springblade.specialperiod.vo.evo.SpecialPeriodEVO;
                        import org.springblade.specialperiod.vo.qvo.SpecialPeriodQVO;
                        public class SpecialPeriodServiceImpl extends BaseServiceImpl<SpecialPeriodMapper, SpecialPeriod> {
                            public IPage<SpecialPeriodEVO> selectPageList(IPage<SpecialPeriodEVO> page, SpecialPeriodQVO qvo) {
                                return baseMapper.selectPageList(page, qvo);
                            }
                            public List<SpecialPeriodEVO> selectStatByCondition(SpecialPeriodQVO qvo) {
                                return baseMapper.selectStatByCondition(qvo);
                            }
                            public boolean validate(SpecialPeriod entity) {
                                java.util.function.Function<SpecialPeriod, Integer> status = SpecialPeriod::getStatus;
                                return entity.getPeriodType() != null;
                            }
                            public boolean enable(List<Long> ids) {
                                this.saveOrUpdate(new SpecialPeriod());
                                return this.changeStatus(ids, 2);
                            }
                        }
                        """));

        List<GeneratedProjectValidator.Issue> issues = new GeneratedTypeClosureValidator().validate(files);
        assertTrue(issues.stream().anyMatch(issue -> "TYPE-ARGUMENT-MISMATCH".equals(issue.rule())), issues::toString);
        assertTrue(issues.stream().filter(issue -> "TYPE-RETURN-MISMATCH".equals(issue.rule())).count() >= 2, issues::toString);
        assertTrue(issues.stream().anyMatch(issue -> "TYPE-METHOD-MISSING".equals(issue.rule())
                && issue.message().contains("getPeriodType")), issues::toString);
        assertTrue(issues.stream().anyMatch(issue -> "TYPE-METHOD-MISSING".equals(issue.rule())
                && issue.message().contains("changeStatus")), issues::toString);
        assertTrue(issues.stream().noneMatch(issue -> issue.message().contains("saveOrUpdate")), issues::toString);
        assertTrue(issues.stream().noneMatch(issue -> issue.message().contains("getStatus")), issues::toString);
    }

    @Test
    void acceptsInjectedGeneratedFieldsAndLombokOrBaseEntitySetters() {
        List<GeneratedFile> files = List.of(
                file("api/Ticket.java", """
                        import lombok.Data;
                        @Data class Ticket extends BaseEntity { private String name; }
                        """),
                file("api/TicketService.java", """
                        interface TicketService { void create(Ticket ticket); }
                        """),
                file("impl/TicketController.java", """
                        class TicketController {
                            private TicketService ticketService;
                            void create(Ticket ticket) {
                                ticket.setName("name");
                                ticket.setStatus(1);
                                ticketService.create(ticket);
                            }
                        }
                        """));

        List<GeneratedProjectValidator.Issue> issues = new GeneratedTypeClosureValidator().validate(files);
        assertTrue(issues.isEmpty(), issues::toString);
    }

    private GeneratedFile file(String path, String content) {
        return GeneratedFile.create(TaskType.OTHER, path, content);
    }
}
