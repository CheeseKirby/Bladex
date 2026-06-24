package org.springblade.aiworkflow.service;

import org.springblade.aiworkflow.vo.StatusUpdateRequest;

public interface IPartACallbackService {

    void notifyStatusUpdate(StatusUpdateRequest request);
}
