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
/* eslint-disable no-var */
import * as React from 'react'
import TextField from '@material-ui/core/TextField'
import MenuItem from '@material-ui/core/MenuItem'
import { hot } from 'react-hot-loader'
import { FilterClass } from '../filter-builder/filter.structure'
import { Omit } from '../../typescript'
import Autocomplete from '@material-ui/lab/Autocomplete'
import TypedMetacardDefs from '../tabs/metacard/metacardDefinitions'
import Chip from '@material-ui/core/Chip'
import Grid from '@material-ui/core/Grid'
import Swath from '../swath/swath'
import properties from '../../js/properties'
import metacardDefinitions from '../singletons/metacard-definitions'
import FilterInput from '../../react-component/filter/filter-input'
import Checkbox from '@material-ui/core/Checkbox'
import FormControlLabel from '@material-ui/core/FormControlLabel'
export interface BasicFilterClass extends Omit<FilterClass, 'property'> {
  property: string[]
}

type QueryTimeProps = {
  value: undefined | BasicFilterClass
  onChange: (e: any) => void
}

const getPossibleProperties = () => {
  return metacardDefinitions.sortedMetacardTypes
    .filter(
      (definition: any) => !definition.hidden && definition.type === 'DATE'
    )
    .map((definition: any) => ({
      label: definition.alias || definition.id,
      value: definition.id,
    })) as [
    {
      label: string
      value: string
    }
  ]
}

const getDefaultPropertiesToApplyTo = (): {
  label: string
  value: string
}[] => {
  return (properties.basicSearchTemporalSelectionDefault || []).map(
    (property: string) => {
      return {
        label: TypedMetacardDefs.getAlias({ attr: property }),
        value: property,
      }
    }
  )
}

const determinePropertiesToApplyTo = ({
  value,
}: {
  value: BasicFilterClass
}): Array<{ label: string; value: string }> => {
  if (value.property) {
    return value.property
      .filter((prop) => prop !== 'anyDate')
      .map((property) => {
        return {
          label: TypedMetacardDefs.getAlias({ attr: property }),
          value: property,
        }
      })
  } else {
    return getDefaultPropertiesToApplyTo()
  }
}

const QueryTime = ({ value, onChange }: QueryTimeProps) => {
  React.useEffect(() => {
    if (value && value.property === undefined) {
      onChange({
        ...value,
        property: determinePropertiesToApplyTo({ value }).map(
          (val) => val.value
        ),
      })
    }
  }, [value])
  if (value && value.property === undefined) {
    return null // the use effect above should fire to take care of setting a default
  }
  return (
    <>
      <div>
        <FormControlLabel
          labelPlacement="end"
          control={
            <Checkbox
              color="default"
              checked={value ? true : false}
              onChange={(e) => {
                if (e.target.checked) {
                  onChange({
                    ...value,
                    type: 'AFTER',
                    property: getDefaultPropertiesToApplyTo().map(
                      (val) => val.value
                    ),
                  })
                } else {
                  onChange(undefined)
                }
              }}
            />
          }
          label="Time"
        />
        {value ? (
          <Grid
            container
            alignItems="stretch"
            direction="column"
            wrap="nowrap"
            className="pt-2"
          >
            <Grid item className="w-full pb-2">
              <Autocomplete
                fullWidth
                multiple
                options={getPossibleProperties()}
                disableCloseOnSelect
                getOptionLabel={(option) => option.label}
                getOptionSelected={(option, value) =>
                  option.value === value.value
                }
                onChange={(_e, newValue) => {
                  onChange({
                    ...value,
                    property: newValue.map((val) => val.value),
                  })
                }}
                size="small"
                renderTags={(tagValue, getTagProps) =>
                  tagValue.map((option, index) => (
                    <Chip
                      variant="outlined"
                      color="default"
                      label={option.label}
                      {...getTagProps({ index })}
                    />
                  ))
                }
                value={determinePropertiesToApplyTo({ value })}
                renderInput={(params) => (
                  <TextField {...params} variant="outlined" />
                )}
              />
            </Grid>
            <Grid
              container
              alignItems="stretch"
              direction="row"
              wrap="nowrap"
              className="pt-2"
            >
              <Grid item>
                <Swath className="w-1 h-full" />
              </Grid>
              <Grid container direction="column">
                <Grid item className="w-full pl-2 pb-2">
                  <TextField
                    fullWidth
                    variant="outlined"
                    size="small"
                    select
                    value={value.type}
                    onChange={(e) => {
                      onChange({
                        ...value,
                        type: e.target.value,
                      })
                    }}
                  >
                    <MenuItem value="AFTER">After</MenuItem>
                    <MenuItem value="BEFORE">Before</MenuItem>
                    <MenuItem value="DURING">Between</MenuItem>
                    <MenuItem value="RELATIVE">Within the last</MenuItem>
                    <MenuItem value="AROUND">Around</MenuItem>
                  </TextField>
                </Grid>
                <Grid item className="w-full pl-2">
                  <FilterInput
                    filter={{ ...value, property: value.property[0] }}
                    setFilter={(val: any) => {
                      onChange({
                        ...value,
                        value: val.value,
                      })
                    }}
                  />
                </Grid>
              </Grid>
            </Grid>
          </Grid>
        ) : null}
      </div>
    </>
  )
}

export default hot(module)(QueryTime)
