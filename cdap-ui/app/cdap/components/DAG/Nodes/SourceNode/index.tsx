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

import withStyles from '@material-ui/core/styles/withStyles';
import { IAbstractNodeProps, AbstractNode } from 'components/DAG/Nodes/AbstractNode';
import { endpointPaintStyles, genericNodeStyles } from 'components/DAG/Nodes/utilities';

const styles = genericNodeStyles();
interface ISourceNodeProps extends IAbstractNodeProps<typeof styles> {}
class SourceNodeComponent extends AbstractNode<ISourceNodeProps> {
  public type = 'source';

  public getEndpointParams = () => {
    return {
      isSource: true,
      maxConnections: -1, // -1 means unlimited connections
      Endpoint: 'Dot',
      EndpointStyle: { radius: 10 },
      Connector: [
        'Flowchart',
        { stub: [10, 15], alwaysRespectStubs: true, cornerRadius: 20, midpoint: 0.2 },
      ],
      paintStyle: endpointPaintStyles,
    };
  };
  public checkForValidIncomingConnection = (connObj) => {
    if (connObj.connection.target.getAttribute('data-node-type') !== 'source') {
      return true;
    }
    return connObj.connection.source.getAttribute('data-node-type') === 'action';
  };
}

const SourceNode = withStyles(styles)(SourceNodeComponent);
export { SourceNode };
