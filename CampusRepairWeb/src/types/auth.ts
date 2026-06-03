export type UserRole = 'STUDENT' | 'WORKER' | 'ADMIN'

export interface LoginForm {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  expiresIn: number
  userId: string
  username: string
  role: UserRole
  displayName: string
}

export interface CurrentUserResponse {
  userId: string
  username: string
  role: UserRole
  displayName: string
}
