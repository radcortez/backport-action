# Backport Action

The purpose of this action is to backport commits between branches.

When a label following the convention `backport-{branch}` is added to a merged Pull Request, the action will try to 
cherry pick all the commits associated to the labeled Pull Request and open a new Pull Request to the target `{branch}` 
with the commits to apply.

If the backport is not possible due to conflicts, the action will make a comment in the original Pull Request that the 
backport failed, and it must be handled manually.

## Inputs

### `github-token`

**Required** The GitHub Token used to create an authenticated client. The Github Token is already set by the Github 
Action itself. Use this if you want to pass in your own Personal Access Token. 

**Default** `${{github.token}}`.

## Example usage

```yaml
name: Backport
on:
  pull_request:
    types:
      - closed
      - labeled

jobs:
  backport:
    runs-on: ubuntu-latest
    name: Backport
    steps:
      - name: Backport
        uses: radcortez/backport-action@main
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
        env:
          GIT_COMMITTER_NAME: ${{ env.GITHUB_ACTOR }}
          GIT_COMMITTER_EMAIL: noreply@github.com
```

The `GIT_COMMITTER_NAME` and `GIT_COMMITTER_EMAIL` are used for the name and email of the cherry pick committer. The 
action maintains the original committer author. These options are optional.

## Standalone

It is also possible to use the Backport Action directly from your local environment or with any other CI. Please refer 
to `backport.java`. This requires [JBang](https://github.com/jbangdev/jbang) to run the script.

The same rules apply. The target Pull Request require backport labels and the script will either create backport Pull 
Request or report issues. 

### Usage

```bash
jbang backport.java <token> <repository> <pullRequestNumber>
```
