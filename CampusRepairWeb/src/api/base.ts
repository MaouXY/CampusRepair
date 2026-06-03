import type { LocationTreeItem, OptionItem } from '@/types/base'

import request from '@/utils/request'

export function listCategoriesApi() {
  return request.get<never, OptionItem[]>('/base/categories')
}

export function listLocationsApi() {
  return request.get<never, OptionItem[]>('/base/locations')
}

export function listLocationTreeApi() {
  return request.get<never, LocationTreeItem[]>('/base/locations/tree')
}
