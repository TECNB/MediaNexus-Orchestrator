package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.mapper.EmbyWatchDetailsMapper;
import org.junit.jupiter.api.Test;

/** 计时、冻结、敏感呈现通过 EmbyWatchCollectionTest 的采集→真实 DB→查询验证。 */
class EmbyWatchDetailsQueryTest {
    private final AuthService auth=mock(AuthService.class);
    private final EmbyWatchDetailsMapper mapper=mock(EmbyWatchDetailsMapper.class);
    private final EmbyWatchDetailsQuery query=new EmbyWatchDetailsQuery(auth,mapper,null);
    @Test void ordinaryUserCannotReadAnyWatchDetails() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN,"仅管理员可查看")).when(auth).requireAdminUser();
        assertThatThrownBy(() -> query.users("day","2026-10-28",null,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.userWorks("user","day","2026-10-28",null,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.sessions("user",null,null,"day","2026-10-28",null,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.media("adult","duration","day","2026-10-28",null,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.viewers("item","adult","day","2026-10-28",null,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.activity("day","2026-10-28",null,1,20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }
    @Test void invalidDateAndSortAreRejectedInsteadOfAppearingEmpty() {
        assertThatThrownBy(() -> query.users("day","2026-02-30",null,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.media("movie","invalid","day","2026-10-28",null,1,20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }
}
