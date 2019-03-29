import { Classes, Icon } from "@blueprintjs/core"
import "@blueprintjs/core/lib/css/blueprint.css"
import { IconNames } from "@blueprintjs/icons"
import "@blueprintjs/icons/lib/css/blueprint-icons.css" // needed for the mosaic tile buttons (expand, close)
import autobind from "autobind-decorator"
import classNames from "classnames"
import _dropRight from "lodash/dropRight"
import PropTypes from "prop-types"
import React, { Component } from "react"
import {
  Corner,
  createBalancedTreeFromLeaves,
  getLeaves,
  getNodeAtPath,
  getOtherDirection,
  getPathToCorner,
  Mosaic,
  MosaicWindow,
  updateTree
} from "react-mosaic-component"
import "react-mosaic-component/react-mosaic-component.css"
import "./MosaicLayout.css"

export default class MosaicLayout extends Component {
  static propTypes = {
    visualizations: PropTypes.arrayOf(
      PropTypes.shape({
        id: PropTypes.string.isRequired,
        icons: PropTypes.arrayOf(PropTypes.string.isRequired).isRequired, // icon names from @blueprintjs/icons IconNames
        title: PropTypes.string.isRequired,
        renderer: PropTypes.func.isRequired
      })
    ).isRequired,
    initialNode: PropTypes.object, // FIXME: actually MosaicNode
    description: PropTypes.string,
    style: PropTypes.object
  }

  constructor(props) {
    super(props)
    this.state = {
      currentNode: this.props.initialNode
    }
  }

  render() {
    return (
      <div className="mosaic-box" style={this.props.style}>
        <div className="mosaic-container">
          {this.props.description && (
            <p className="chart-description">{this.props.description}</p>
          )}
          {this.renderNavBar()}
          <Mosaic
            value={this.state.currentNode}
            onChange={this.updateCurrentNode}
            renderTile={(id, path) => {
              const viz = this.props.visualizations.find(viz => viz.id === id)
              return (
                <MosaicWindow title={viz.title} path={path}>
                  {viz.renderer(id)}
                </MosaicWindow>
              )
            }}
          />
        </div>
      </div>
    )
  }

  @autobind
  renderNavBar() {
    return (
      <div className={classNames(Classes.NAVBAR)}>
        <div className={classNames(Classes.NAVBAR_GROUP, Classes.BUTTON_GROUP)}>
          <Icon iconSize={Icon.SIZE_LARGE} icon={IconNames.MENU} />
          <button
            className={classNames(Classes.BUTTON)}
            onClick={this.autoArrange}
            title="Auto Arrange"
          >
            <Icon icon={IconNames.GRID_VIEW} />
          </button>
          {this.renderButtons()}
        </div>
      </div>
    )
  }

  renderButtons() {
    const buttons = []
    const leaves = getLeaves(this.state.currentNode)
    this.props.visualizations.forEach(viz => {
      if (!leaves.includes(viz.id)) {
        buttons.push(
          <button
            key={viz.id}
            className={classNames(Classes.BUTTON)}
            onClick={this.addChart.bind(this, viz.id)}
            title={viz.title}
          >
            {viz.icons.map((icon, i) => (
              <Icon key={i} icon={icon} />
            ))}
          </button>
        )
      }
    })
    return buttons
  }

  @autobind
  updateCurrentNode(currentNode) {
    this.setState({ currentNode })
  }

  autoArrange = () => {
    const leaves = getLeaves(this.state.currentNode)
    this.updateCurrentNode(createBalancedTreeFromLeaves(leaves))
  }

  addChart = viz => {
    let { currentNode } = this.state
    if (!currentNode) {
      currentNode = viz
    } else {
      const path = getPathToCorner(currentNode, Corner.TOP_RIGHT)
      const parent = getNodeAtPath(currentNode, _dropRight(path))
      const destination = getNodeAtPath(currentNode, path)
      const direction = parent ? getOtherDirection(parent.direction) : "row"
      let first
      let second
      if (direction === "row") {
        first = destination
        second = viz
      } else {
        first = viz
        second = destination
      }
      currentNode = updateTree(currentNode, [
        {
          path,
          spec: {
            $set: {
              direction,
              first,
              second
            }
          }
        }
      ])
    }
    this.updateCurrentNode(currentNode)
  }
}
