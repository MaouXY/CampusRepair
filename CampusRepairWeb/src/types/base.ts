export interface OptionItem {
  id: string
  parentId: string | null
  name: string
}

export interface LocationTreeItem extends OptionItem {
  children: LocationTreeItem[]
}
