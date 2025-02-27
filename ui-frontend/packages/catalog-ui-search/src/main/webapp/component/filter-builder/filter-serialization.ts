/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
import Backbone from 'backbone'

import CQLUtils from '../../js/CQLUtils'
import metacardDefinitions from '../singletons/metacard-definitions'
import _ from 'underscore'

const FilterBuilderModel = Backbone.Model.extend({
  defaults() {
    return {
      operator: 'AND',
      sortableOrder: 0,
    }
  },
  type: 'filter-builder',
})

const FilterModel = Backbone.Model.extend({
  defaults() {
    return {
      value: [''],
      type: 'anyText',
      comparator: 'CONTAINS',
      sortableOrder: 0,
    }
  },
  type: 'filter',
})

const comparatorToCQL = {
  BEFORE: 'BEFORE',
  AFTER: 'AFTER',
  RELATIVE: '=',
  BETWEEN: 'DURING',
  INTERSECTS: 'INTERSECTS',
  DWITHIN: 'DWITHIN',
  CONTAINS: 'ILIKE',
  MATCHCASE: 'LIKE',
  EQUALS: '=',
  'IS EMPTY': 'IS NULL',
  '>': '>',
  '<': '<',
  '=': '=',
  '<=': '<=',
  '>=': '>=',
  RANGE: 'BETWEEN',
} as { [key: string]: string }

const cqlToComparator = Object.keys(comparatorToCQL).reduce((mapping, key) => {
  const value = comparatorToCQL[key]
  mapping[value] = key
  return mapping
}, {} as { [key: string]: string })

const transformFilter = (filter: any) => {
  const { type, property } = filter

  const value = CQLUtils.isGeoFilter(filter.type) ? filter : filter.value

  if (_.isObject(property)) {
    // if the filter is something like NEAR (which maps to a CQL filter function such as 'proximity'),
    // there is an enclosing filter that creates the necessary '= TRUE' predicate, and the 'property'
    // attribute is what actually contains that proximity() call.
    const { filterFunctionName, params } = property

    if (filterFunctionName !== 'proximity') {
      throw new Error(
        'Unsupported filter function in filter view: ' + filterFunctionName
      )
    }

    const [type, distance, value] = params

    return {
      // this is confusing but 'type' on the model is actually the name of the property we're filtering on
      type,
      comparator: 'NEAR',
      value: [{ value, distance }],
    }
  }

  const definition = metacardDefinitions.metacardTypes[property]

  const comparator =
    definition && definition.type === 'DATE' && type === '='
      ? 'RELATIVE'
      : cqlToComparator[type]

  let parsedValue
  if (type === 'DURING') {
    if (filter.value.includes('/')) {
      const dates = filter.value.split('/')
      filter.from = dates[0] || null
      filter.to = dates[1] || null
    }
    parsedValue = `${filter.from}/${filter.to}`
  } else if (type === 'BETWEEN') {
    parsedValue = { lower: filter.lowerBoundary, upper: filter.upperBoundary }
  } else {
    parsedValue = value
  }

  return {
    type: property,
    comparator,
    value: [parsedValue],
  }
}

const FilterBuilderCollection = Backbone.Collection.extend({
  model(attrs: any, { collection }: any) {
    const sortableOrder = collection.length + 1

    if (attrs.filterBuilder === true) {
      const operator = attrs.type
      return new FilterBuilderModel({
        operator,
        sortableOrder,
        filters: new FilterBuilderCollection([defaultFilter]),
        ...attrs,
      })
    }

    return new FilterModel({
      sortableOrder,
      ...attrs,
      ...transformFilter(attrs),
    })
  },
})

// model->json
export const serialize = (model: any) => {
  if (model instanceof FilterBuilderModel) {
    const operator = model.get('operator')
    const filters = model.get('filters') || []

    if (operator === 'NONE') {
      return {
        type: 'NOT',
        filters: [
          {
            type: 'AND',
            filters: filters.map(serialize),
          },
        ],
      }
    }
    return {
      type: operator,
      filters: filters.map(serialize).filter((filter: any) => filter),
    }
  }

  if (model instanceof FilterModel) {
    const property = model.get('type')
    const comparator = model.get('comparator')
    const value = model.get('value')[0]
    const type = comparatorToCQL[comparator]

    let filter
    switch (comparator) {
      case 'NEAR':
        filter = CQLUtils.generateFilterForFilterFunction('proximity', [
          property,
          value.distance,
          value.value,
        ])
        break
      case 'IS EMPTY':
        filter = CQLUtils.generateIsEmptyFilter(property)
        break
      default:
        filter = CQLUtils.generateFilter(
          type,
          property,
          value === undefined ? '' : value
        )
        break
    }
    return {
      ...filter,
      extensionData: model.get('extensionData'),
    }
  }
}

const defaultFilter = { type: 'ILIKE', property: 'anyText', value: '' }

// json->model
export const deserialize = (filter: any = defaultFilter): any => {
  const { type, filters } = filter

  if (!filters) {
    return deserialize({ type: 'AND', filters: [filter] })
  }

  return new FilterBuilderModel({
    operator: type,
    filters: new FilterBuilderCollection(
      filters.map((filter: any) =>
        filter.filters !== undefined ? deserialize(filter) : filter
      )
    ),
  })
}
