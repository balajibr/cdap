/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

const { PIPELINE_PROGRAMS_MAP } = require('./common');

async function pipelineRunsResolver(parent, args, context) {
  const namespace = context.namespace;
  const name = parent.name;

  const pipelineType = parent.artifact.name || 'cdap-data-pipeline';

  const { programType, programId } = PIPELINE_PROGRAMS_MAP[pipelineType];

  const program = {
    appId: name,
    programType: programType,
    programId: programId,
  };

  const runInfo = await context.loaders.programRuns.load({
    namespace,
    program,
  });

  if (runInfo.length === 0) {
    return [];
  }

  return runInfo.runs;
}

module.exports = {
  pipelineRunsResolver,
};
