/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Intent } from '@blueprintjs/core';
import { IconNames } from '@blueprintjs/icons';
import React, { useState } from 'react';

import type { FormJsonTabs } from '../../components';
import { AutoForm, ExternalLink, FormJsonSelector, JsonInput, Loader } from '../../components';
import type { CoordinatorDynamicConfig } from '../../druid-models';
import { COORDINATOR_DYNAMIC_CONFIG_FIELDS } from '../../druid-models';
import { useQueryManager } from '../../hooks';
import { getLink } from '../../links';
import { Api, AppToaster } from '../../singletons';
import { getApiArray, getDruidErrorMessage } from '../../utils';
import { SnitchDialog } from '..';

import { COORDINATOR_DYNAMIC_CONFIG_COMPLETIONS } from './coordinator-dynamic-config-completions';

import './coordinator-dynamic-config-dialog.scss';

export interface CoordinatorDynamicConfigDialogProps {
  onClose(): void;
}

export const CoordinatorDynamicConfigDialog = React.memo(function CoordinatorDynamicConfigDialog(
  props: CoordinatorDynamicConfigDialogProps,
) {
  const { onClose } = props;
  const [currentTab, setCurrentTab] = useState<FormJsonTabs>('form');
  const [dynamicConfig, setDynamicConfig] = useState<CoordinatorDynamicConfig | undefined>();
  const [jsonError, setJsonError] = useState<Error | undefined>();

  const [historyRecordsState] = useQueryManager<null, any[]>({
    initQuery: null,
    processQuery: async (_, cancelToken) => {
      return await getApiArray(`/druid/coordinator/v1/config/history?count=100`, cancelToken);
    },
  });

  useQueryManager<null, Record<string, any>>({
    initQuery: null,
    processQuery: async (_, cancelToken) => {
      try {
        const configResp = await Api.instance.get('/druid/coordinator/v1/config', { cancelToken });
        setDynamicConfig(configResp.data || {});
      } catch (e) {
        AppToaster.show({
          icon: IconNames.ERROR,
          intent: Intent.DANGER,
          message: `Could not load coordinator dynamic config: ${getDruidErrorMessage(e)}`,
        });
        onClose();
      }
      return {};
    },
  });

  async function saveConfig(comment: string) {
    try {
      await Api.instance.post('/druid/coordinator/v1/config', dynamicConfig, {
        headers: {
          'X-Druid-Author': 'console',
          'X-Druid-Comment': comment,
        },
      });
    } catch (e) {
      AppToaster.show({
        icon: IconNames.ERROR,
        intent: Intent.DANGER,
        message: `Could not save coordinator dynamic config: ${getDruidErrorMessage(e)}`,
      });
    }

    AppToaster.show({
      message: 'Saved coordinator dynamic config',
      intent: Intent.SUCCESS,
    });
    onClose();
  }

  return (
    <SnitchDialog
      className="coordinator-dynamic-config-dialog"
      saveDisabled={Boolean(jsonError)}
      onSave={comment => void saveConfig(comment)}
      onClose={onClose}
      title="Coordinator dynamic config"
      historyRecords={historyRecordsState.data}
    >
      {dynamicConfig ? (
        <>
          <p>
            Edit the coordinator dynamic configuration on the fly. For more information please refer
            to the{' '}
            <ExternalLink href={`${getLink('DOCS')}/configuration/#dynamic-configuration`}>
              documentation
            </ExternalLink>
            .
          </p>
          <FormJsonSelector
            tab={currentTab}
            onChange={t => {
              setJsonError(undefined);
              setCurrentTab(t);
            }}
          />
          {currentTab === 'form' ? (
            <AutoForm
              fields={COORDINATOR_DYNAMIC_CONFIG_FIELDS}
              model={dynamicConfig}
              onChange={setDynamicConfig}
            />
          ) : (
            <JsonInput
              value={dynamicConfig}
              height="50vh"
              onChange={setDynamicConfig}
              setError={setJsonError}
              jsonCompletions={COORDINATOR_DYNAMIC_CONFIG_COMPLETIONS}
            />
          )}
        </>
      ) : (
        <Loader />
      )}
    </SnitchDialog>
  );
});
