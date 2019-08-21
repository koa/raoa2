import {UserData} from './user.data';

export type AuthenticationStateEnum =
  'UNKNOWN' |
  'AUTHENTICATED' |
  'AUTHORIZATION_REQUESTED' |
  'AUTHORIZED';


export interface AuthenticationState {
  state: AuthenticationStateEnum;
  user: UserData;
}
