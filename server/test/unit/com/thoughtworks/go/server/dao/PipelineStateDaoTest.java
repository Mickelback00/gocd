/*
 * Copyright 2017 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.server.dao;

import com.thoughtworks.go.database.Database;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.PipelineState;
import com.thoughtworks.go.domain.Stage;
import com.thoughtworks.go.helper.PipelineMother;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.service.StubGoCache;
import com.thoughtworks.go.server.transaction.TestTransactionSynchronizationManager;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import org.hibernate.SessionFactory;
import org.hibernate.classic.Session;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.UUID;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class PipelineStateDaoTest {

    private GoCache goCache;
    private PipelineStateDao pipelineStateDao;
    private TransactionTemplate transactionTemplate;
    private org.hibernate.SessionFactory mockSessionFactory;
    private Session session;

    @Before
    public void setup() throws Exception {
        goCache = new StubGoCache(new TestTransactionSynchronizationManager());
        goCache.clear();
        mockSessionFactory = mock(SessionFactory.class);
        transactionTemplate = mock(TransactionTemplate.class);
        pipelineStateDao = new PipelineStateDao(null, null, goCache, null, transactionTemplate, null,
                null, null, null, mock(Database.class), mockSessionFactory);
        session = mock(Session.class);
        when(mockSessionFactory.getCurrentSession()).thenReturn(session);
    }

    @Test
    public void shouldNotCorruptCacheIfSaveFailsWhileLocking() {
        String pipelineName = UUID.randomUUID().toString();
        Pipeline pipeline = PipelineMother.pipeline(pipelineName, new Stage());
        PipelineState pipelineState = new PipelineState(pipelineName);
        goCache.put(pipelineStateDao.pipelineLockStateCacheKey(pipelineName), pipelineState);

        when(transactionTemplate.execute(any(org.springframework.transaction.support.TransactionCallbackWithoutResult.class))).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                org.springframework.transaction.support.TransactionCallbackWithoutResult callback = (org.springframework.transaction.support.TransactionCallbackWithoutResult) invocation.getArguments()[0];
                callback.doInTransaction(new SimpleTransactionStatus());
                return null;
            }
        });
        doThrow(new RuntimeException("could not save!")).when(session).saveOrUpdate(any(PipelineState.class));

        try {
            pipelineStateDao.lockPipeline(pipeline);
            fail("save should have thrown an exception!");
        } catch (Exception e) {
            PipelineState stateFromCache = (PipelineState) goCache.get(pipelineStateDao.pipelineLockStateCacheKey(pipelineName));
            assertThat(stateFromCache, is(nullValue()));
        }
    }

    @Test
    public void shouldNotCorruptCacheIfSaveFailsWhileUnLocking() {
        String pipelineName = UUID.randomUUID().toString();
        PipelineState pipelineState = new PipelineState(pipelineName);
        pipelineState.lock(1);
        goCache.put(pipelineStateDao.pipelineLockStateCacheKey(pipelineName), pipelineState);

        when(transactionTemplate.execute(any(org.springframework.transaction.support.TransactionCallbackWithoutResult.class))).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                org.springframework.transaction.support.TransactionCallbackWithoutResult callback = (org.springframework.transaction.support.TransactionCallbackWithoutResult) invocation.getArguments()[0];
                callback.doInTransaction(new SimpleTransactionStatus());
                return null;
            }
        });
        doThrow(new RuntimeException("could not save!")).when(session).saveOrUpdate(any(PipelineState.class));

        try {
            pipelineStateDao.unlockPipeline(pipelineName);
            fail("save should have thrown an exception!");
        } catch (Exception e) {
            PipelineState stateFromCache = (PipelineState) goCache.get(pipelineStateDao.pipelineLockStateCacheKey(pipelineName));
            assertThat(stateFromCache, is(nullValue()));
        }
    }
}