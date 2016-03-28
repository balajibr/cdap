/*
 * Copyright © 2014-2016 Cask Data, Inc.
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
package co.cask.cdap.internal.app.runtime.workflow;

import co.cask.cdap.api.app.ApplicationSpecification;
import co.cask.cdap.api.spark.Spark;
import co.cask.cdap.api.spark.SparkSpecification;
import co.cask.cdap.api.workflow.Workflow;
import co.cask.cdap.api.workflow.WorkflowNodeState;
import co.cask.cdap.api.workflow.WorkflowSpecification;
import co.cask.cdap.api.workflow.WorkflowToken;
import co.cask.cdap.app.program.Program;
import co.cask.cdap.app.runtime.ProgramOptions;
import co.cask.cdap.app.runtime.ProgramRunnerFactory;
import com.google.common.base.Preconditions;

import java.util.Map;

/**
 * Creates {@link Runnable} for executing {@link Spark} programs from {@link Workflow}.
 */
final class SparkProgramWorkflowRunner extends AbstractProgramWorkflowRunner {

  SparkProgramWorkflowRunner(WorkflowSpecification workflowSpec, ProgramRunnerFactory programRunnerFactory,
                             Program workflowProgram, ProgramOptions workflowProgramOptions, WorkflowToken token,
                             String nodeId, Map<String, WorkflowNodeState> nodeStates) {
    super(workflowProgram, workflowProgramOptions, programRunnerFactory, workflowSpec, token, nodeId, nodeStates);
  }

  /**
   * Gets the Specification of the program by its name from the {@link WorkflowSpecification}. Creates an
   * appropriate {@link Program} using this specification through a suitable concrete implementation of
   * {@link AbstractWorkflowProgram} and then gets the {@link Runnable} for the program which can be called
   * to execute the program.
   *
   * @param name name of the program in the workflow
   * @return {@link Runnable} for the program.
   */
  @Override
  public Runnable create(String name) {
    ApplicationSpecification spec = workflowProgram.getApplicationSpecification();
    final SparkSpecification sparkSpec = spec.getSpark().get(name);
    Preconditions.checkArgument(sparkSpec != null,
                                "No Spark with name %s found in Workflow %s", name, workflowSpec.getName());

    final Program sparkProgram = new WorkflowSparkProgram(workflowProgram, sparkSpec);
    return getProgramRunnable(name, sparkProgram);
  }
}
